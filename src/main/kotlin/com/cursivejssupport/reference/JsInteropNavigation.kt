package com.cursivejssupport.reference

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NpmPackageResolver
import com.cursivejssupport.npm.NsAliasResolver
import com.cursivejssupport.util.JsInteropChain
import com.cursivejssupport.util.JsInteropPsi
import com.cursivejssupport.util.JsResolveUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import cursive.psi.api.ClList
import cursive.psi.api.symbols.ClSymbol
import cursive.psi.impl.symbols.ClEditorSymbol

/**
 * Shared navigation / resolve logic for go-to-declaration and [JsSymbolReference].
 */
object JsInteropNavigation {

    private val log = logger<JsInteropNavigation>()

    private fun unwrapMemberNavigationWrapper(element: PsiElement): PsiElement =
        (element as? JsMemberNavigationTarget)?.indexedLeaf ?: element

    private fun firstGlobalPsi(project: Project, index: JsSymbolIndex, name: String): PsiElement? =
        index.getGlobalPsiElements(project, name)?.firstOrNull()?.let { unwrapMemberNavigationWrapper(it) }

    private fun firstMemberPsi(project: Project, index: JsSymbolIndex, typeName: String, member: String): PsiElement? =
        index.getMemberPsiElements(project, typeName, member)?.firstOrNull()?.let { unwrapMemberNavigationWrapper(it) }

    private fun firstNpmExportPsi(project: Project, index: JsSymbolIndex, pkg: String, export: String): PsiElement? =
        index.getNpmExportPsiElements(project, pkg, export)?.firstOrNull()?.let { unwrapMemberNavigationWrapper(it) }

    private fun firstPsiFromPackageTypingsEntry(project: Project, packageName: String): PsiElement? {
        val ioFile = NpmPackageResolver(project).typingsEntryFile(packageName) ?: return null
        val vf = LocalFileSystem.getInstance().findFileByPath(ioFile.absolutePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
        if (psiFile.textLength == 0) return null
        return psiFile.findElementAt(0) ?: psiFile
    }

    fun expandTargetElement(sourceElement: PsiElement): PsiElement =
        JsInteropPsi.expandTargetElement(sourceElement)

    fun resolveGotoTargets(
        sourceElement: PsiElement,
        project: Project,
    ): Array<PsiElement>? {
        val text = JsInteropPsi.gotoSymbolText(sourceElement)
        if (text.isBlank() || text.startsWith("\"") || text.startsWith("(") || text.startsWith("[")) {
            return null
        }

        if (log.isDebugEnabled) log.debug("Goto declaration at symbol text='$text'")

        val file = sourceElement.containingFile ?: return null
        val index = JsSymbolIndex.getInstance()
        val aliases = NsAliasResolver.resolveAliases(file)

        val namespace = if (text.contains("/")) text.substringBefore("/") else text
        val exportName = if (text.contains("/")) text.substringAfter("/") else null

        if (aliases.containsKey(namespace)) {
            val packageName = aliases[namespace]!!
            if (exportName != null && exportName != namespace) {
                index.getNpmExportPsiElements(project, packageName, exportName)?.let { return it }
            } else {
                index.getNpmExportPsiElements(project, packageName, "default")?.let { return it }
                    ?: firstPsiFromPackageTypingsEntry(project, packageName)?.let { return arrayOf(it) }
            }
        }

        if (text.startsWith("js/") || (JsInteropPsi.enclosingEditorSymbol(sourceElement)?.namespace == "js")) {
            val segments = when {
                text.startsWith("js/") -> JsInteropChain.segmentsFromFullText(text) ?: emptyList()
                else -> {
                    val sym = JsInteropPsi.enclosingEditorSymbol(sourceElement)
                    JsInteropChain.segmentsFromSymbol(sym?.namespace, sym?.name, text) ?: emptyList()
                }
            }
            when {
                segments.isEmpty() -> { }
                segments.size == 1 ->
                    index.getGlobalPsiElements(project, segments[0])?.let { return it }
                else -> {
                    val parentType = index.resolveJsChainType(segments.dropLast(1)) ?: return null
                    index.getMemberPsiElements(project, parentType, segments.last())?.let { return it }
                }
            }
        }

        if (text.startsWith(".") || text.startsWith(".-")) {
            val memberName = text.removePrefix(".-").removePrefix(".")
            var targets: Array<PsiElement>? = null

            var listElement: PsiElement? = sourceElement
            while (listElement != null && !listElement.text.startsWith("(")) {
                listElement = listElement.parent
            }

            var preferredType: String? = null
            if (listElement != null) {
                val children = listElement.children.filter {
                    it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")"
                }
                val headText = JsInteropPsi.enclosingEditorSymbol(children.getOrNull(0) ?: sourceElement)?.text
                    ?: children.getOrNull(0)?.text
                if (children.size > 1 && headText == text) {
                    val receiver = children[1]
                    if (log.isDebugEnabled) log.debug("Member goto receiver='${receiver.text}'")

                    val receiverText = JsInteropPsi.enclosingEditorSymbol(receiver)?.text ?: receiver.text
                    var typeName = JsResolveUtil.resolveType(receiver, index)
                    if (typeName == null && receiverText.startsWith("js/")) {
                        val chain = JsInteropChain.segmentsFromFullText(receiverText)
                        if (chain != null && chain.isNotEmpty()) {
                            typeName = index.resolveJsChainType(chain)
                        }
                    }
                    if (typeName == null && receiver is ClEditorSymbol && receiver.namespace == "js") {
                        val nm = receiver.name
                        if (nm != null && nm.contains('.')) {
                            typeName = index.resolveJsChainType(nm.split('.').map { it.trim() }.filter { it.isNotEmpty() })
                        }
                    }
                    if (typeName == null && receiver.text.startsWith("js/")) {
                        val globalName = receiverText.removePrefix("js/")
                        typeName = index.resolveGlobalType(globalName)
                    }
                    preferredType = typeName
                    if (typeName != null) {
                        targets = index.getMemberPsiElements(project, typeName, memberName)
                    }
                }
            }

            if (targets == null) {
                targets = index.getAnyMemberPsiElements(project, memberName, preferredReceiverType = preferredType)
            }

            if (targets != null) {
                if (log.isDebugEnabled) log.debug("Resolved method .$memberName to ${targets.size} target(s)")
                return targets
            }
        }

        return null
    }

    fun resolveClSymbol(symbol: PsiElement, project: Project): PsiElement? {
        val (text, namespace, name) = interopSymbolTextNamespaceName(symbol) ?: return null
        val index = JsSymbolIndex.getInstance()

        return when {
            text == "js" ->
                JsSymbolPsiElement(symbol.manager, symbol.language, "js", "Window", "JavaScript Global Namespace (Window)")

            text.startsWith("js/") || (namespace == "js") -> {
                val segments = JsInteropChain.segmentsFromSymbol(namespace, name, text) ?: return null
                when {
                    segments.isEmpty() ->
                        JsSymbolPsiElement(symbol.manager, symbol.language, "js", "Window", "JavaScript Global Namespace (Window)")

                    segments.size == 1 ->
                        resolveGlobal(segments[0], index, symbol, project)

                    else -> {
                        val parentType = index.resolveJsChainType(segments.dropLast(1)) ?: return null
                        val last = segments.last()
                        val member = index.resolveInterface(parentType)?.members?.get(last)?.firstOrNull()
                            ?: return null
                        val phys = firstMemberPsi(project, index, parentType, last)
                        JsSymbolPsiElement(
                            symbol.manager, symbol.language, last, parentType, member.doc,
                            member = member, navigationTarget = phys,
                        )
                    }
                }
            }

            text.startsWith(".") -> {
                val memberName = text.removePrefix(".").removePrefix("-")
                val list = symbol.parent as? ClList ?: return null
                val children = list.children.filter { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" }
                val receiver = children.getOrNull(1)

                val typeName = JsResolveUtil.resolveType(receiver, index)
                if (typeName != null) {
                    val iface = index.resolveInterface(typeName)
                    val member = iface?.members?.get(memberName)?.firstOrNull()
                    if (member != null) {
                        val phys = firstMemberPsi(project, index, typeName, memberName)
                        JsSymbolPsiElement(
                            symbol.manager, symbol.language, memberName, typeName, member.doc,
                            member = member, navigationTarget = phys,
                        )
                    } else null
                } else {
                    val targets = index.getAnyMemberPsiElements(project, memberName, null) ?: return null
                    val first = targets.firstOrNull() ?: return null
                    val phys = unwrapMemberNavigationWrapper(first)
                    val ifaceName = (first as? JsMemberNavigationTarget)?.declaringTypeName
                    val memberDoc = ifaceName?.let { index.resolveInterface(it)?.members?.get(memberName)?.firstOrNull()?.doc }
                    JsSymbolPsiElement(
                        symbol.manager, symbol.language, memberName, ifaceName, memberDoc,
                        navigationTarget = phys,
                    )
                }
            }

            namespace != null -> {
                val file = symbol.containingFile ?: return null
                val aliases = NsAliasResolver.resolveAliases(file)
                val pkg = aliases[namespace] ?: return null
                val export = name ?: return null
                if (index.isKnownNpmExport(pkg, export)) {
                    val phys = firstNpmExportPsi(project, index, pkg, export)
                        ?: firstPsiFromPackageTypingsEntry(project, pkg)
                    JsSymbolPsiElement(
                        symbol.manager, symbol.language, export, null, null,
                        packageName = pkg, npmExportName = export, navigationTarget = phys,
                    )
                } else null
            }

            else -> {
                val file = symbol.containingFile ?: return null
                val aliases = NsAliasResolver.resolveAliases(file)
                val pkg = aliases[text] ?: return null
                val exportKey = when {
                    index.isKnownNpmExport(pkg, "default") -> "default"
                    index.isKnownNpmExport(pkg, text) -> text
                    else -> index.npmExportNames(pkg).firstOrNull()
                } ?: return null
                val phys = firstNpmExportPsi(project, index, pkg, exportKey)
                    ?: firstPsiFromPackageTypingsEntry(project, pkg)
                JsSymbolPsiElement(
                    symbol.manager, symbol.language, text, null, null,
                    packageName = pkg, npmExportName = exportKey, navigationTarget = phys,
                )
            }
        }
    }

    private fun interopSymbolTextNamespaceName(symbol: PsiElement): Triple<String, String?, String?>? {
        val sym = symbol as? ClSymbol ?: return null
        val text = (sym.text ?: "").trim()
        val namespace = sym.namespace
        val name = sym.name ?: if (text == "js") "js" else null
        return Triple(text, namespace, name)
    }

    private fun resolveGlobal(name: String, index: JsSymbolIndex, symbol: PsiElement, project: Project): PsiElement? {
        val varInfo = index.resolveGlobalInfo(name)
        if (varInfo != null) {
            val phys = firstGlobalPsi(project, index, name)
            return JsSymbolPsiElement(
                symbol.manager, symbol.language, name, varInfo.type, varInfo.doc,
                navigationTarget = phys,
            )
        }
        val overloads = index.resolveFunctions(name)
        if (overloads != null && overloads.isNotEmpty()) {
            val phys = firstGlobalPsi(project, index, name)
            return JsSymbolPsiElement(
                symbol.manager, symbol.language, name, "function", overloads.first().doc,
                member = overloads.first(), navigationTarget = phys,
            )
        }
        return null
    }
}
