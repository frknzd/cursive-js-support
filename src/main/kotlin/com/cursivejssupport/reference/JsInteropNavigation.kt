package com.cursivejssupport.reference

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NpmPackageResolver
import com.cursivejssupport.npm.NsAliasResolver
import com.cursivejssupport.util.JsInteropChain
import com.cursivejssupport.util.JsInteropPsi
import com.cursivejssupport.util.JsResolveUtil
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
 * Shared navigation / resolve logic for go-to-declaration and the symbol reference contributor.
 */
object JsInteropNavigation {

    private fun unwrapMemberNavigationWrapper(element: PsiElement): PsiElement =
        (element as? JsMemberNavigationTarget)?.indexedLeaf ?: element

    private fun firstGlobalPsi(project: Project, index: JsSymbolIndex, name: String): PsiElement? =
        index.getGlobalPsiElements(project, name)?.firstOrNull()?.let { unwrapMemberNavigationWrapper(it) }

    private fun firstMemberPsi(project: Project, index: JsSymbolIndex, typeName: String, member: String): PsiElement? =
        index.getMemberPsiElements(project, typeName, member)?.firstOrNull()

    private fun firstNpmExportPsi(project: Project, index: JsSymbolIndex, pkg: String, export: String): PsiElement? =
        index.getNpmExportPsiElements(project, pkg, export)?.firstOrNull()?.let { unwrapMemberNavigationWrapper(it) }

    private fun firstPsiFromPackageTypingsEntry(
        project: Project,
        packageName: String,
        anchorFilePath: String?,
    ): PsiElement? {
        val ioFile = NpmPackageResolver(project).typingsEntryFile(packageName, anchorFilePath) ?: return null
        val vf = LocalFileSystem.getInstance().findFileByPath(ioFile.absolutePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
        if (psiFile.textLength == 0) return null
        return psiFile.findElementAt(0) ?: psiFile
    }

    /**
     * Public entry point for goto-declaration. Defers to [resolveGotoTargetsRaw] and then
     * removes duplicates that point at the exact same source location, which is common when the
     * same member is reachable via multiple base interfaces (e.g. `scrollTop` through
     * `HTMLElement`, `Element`, and `SVGElement`).
     */
    fun resolveGotoTargets(sourceElement: PsiElement, project: Project): Array<PsiElement>? {
        val raw = resolveGotoTargetsRaw(sourceElement, project) ?: return null
        return distinctByLocation(raw)
    }

    private fun distinctByLocation(targets: Array<PsiElement>): Array<PsiElement>? {
        val seen = HashSet<String>()
        val out = ArrayList<PsiElement>(targets.size)
        for (element in targets) {
            val key = locationKey(element)
            if (seen.add(key)) out.add(element)
        }
        if (out.isEmpty()) return null
        return out.toTypedArray()
    }

    private fun locationKey(element: PsiElement): String {
        val nav = (element as? JsMemberNavigationTarget)?.indexedLeaf ?: element
        val file = nav.containingFile?.virtualFile?.path
        if (file != null) {
            val offset = runCatching { nav.textRange?.startOffset ?: -1 }.getOrDefault(-1)
            // Include the declaring interface so genuinely-different rows with the same source
            // file/offset (e.g. typeof companion vs interface) stay distinct.
            val iface = (element as? JsMemberNavigationTarget)?.declaringTypeName
            return "$file:$offset:${iface ?: ""}"
        }
        return "id:${System.identityHashCode(element)}"
    }

    private fun resolveGotoTargetsRaw(
        sourceElement: PsiElement,
        project: Project,
    ): Array<PsiElement>? {
        var text = JsInteropPsi.gotoSymbolText(sourceElement)
        if (text.isBlank() || text.startsWith("\"") || text.startsWith("(") || text.startsWith("[")) {
            return null
        }

        // Support constructor calls: (Fuse. ...)
        if (text.endsWith(".") && text.length > 1 && !text.startsWith(".")) {
            text = text.removeSuffix(".")
        }

        val file = sourceElement.containingFile ?: return null
        val index = JsSymbolIndex.getInstance()
        val aliases = NsAliasResolver.resolveAliases(file)

        val namespace = if (text.contains("/")) text.substringBefore("/") else text
        val exportName = if (text.contains("/")) text.substringAfter("/") else null

        if (aliases.containsKey(namespace)) {
            val packageName = aliases[namespace]!!
            val anchorPath = file.virtualFile?.path
            if (exportName != null && exportName != namespace) {
                resolveNpmAliasExportOrMemberTargets(project, index, packageName, exportName)?.let { return it }
            } else {
                val targets = mutableListOf<PsiElement>()
                
                // Prioritize external library target
                index.getNpmExportPsiElements(project, packageName, "default")?.let { targets.addAll(it) }
                firstPsiFromPackageTypingsEntry(project, packageName, anchorPath)?.let { targets.add(it) }
                
                // Include the local alias declaration as a secondary target
                NsAliasResolver.resolveAliasDeclaration(file, namespace)?.let { targets.add(it) }

                if (targets.isNotEmpty()) return targets.toTypedArray()
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
                segments.isEmpty() -> Unit
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

            if (targets != null) return targets
        }

        return null
    }

    private fun resolveNpmAliasExportOrMemberTargets(
        project: Project,
        index: JsSymbolIndex,
        packageName: String,
        exportTail: String,
    ): Array<PsiElement>? {
        val exportKey = exportTail.substringBefore('.').substringBefore('/').trim()
        if (exportKey.isEmpty()) return null
        
        if (!index.isKnownNpmExport(packageName, exportKey)) {
            // Check if it's a member of the default export (common in libraries using export =)
            val defaultType = index.resolveNpmExportType(packageName, "default")
            if (defaultType != null) {
                val targets = index.getMemberPsiElements(project, defaultType, exportKey)
                if (targets != null) {
                    val remainder = exportTail.removePrefix(exportKey).trimStart('.').trim()
                    if (remainder.isEmpty()) return targets
                    
                    // Recurse for nested members if needed
                    val baseType = index.resolveMember(defaultType, exportKey)?.first?.let {
                        if (it.kind == "method") it.returns else it.type
                    } ?: return targets
                    return resolveNestedMemberTargets(project, index, baseType, remainder)
                }
            }

            if (index.isKnownNpmExport(packageName, exportTail)) {
                return index.getNpmExportPsiElements(project, packageName, exportTail)
            }
            return null
        }
        val remainder = exportTail.removePrefix(exportKey).let { tail ->
            when {
                tail.startsWith('/') -> ""
                else -> tail.trimStart('.').trim()
            }
        }
        if (remainder.isEmpty()) {
            return index.getNpmExportPsiElements(project, packageName, exportKey)
        }
        val baseType = index.resolveNpmExportType(packageName, exportKey) ?: return null
        return resolveNestedMemberTargets(project, index, baseType, remainder)
    }

    private fun resolveNestedMemberTargets(
        project: Project,
        index: JsSymbolIndex,
        baseType: String,
        remainder: String,
    ): Array<PsiElement>? {
        val memberSegs = remainder.split('.')
            .map { it.substringBefore('/').trim() }
            .filter { it.isNotEmpty() }
        if (memberSegs.isEmpty()) return null
        
        var receiverType = baseType
        for (seg in memberSegs.dropLast(1)) {
            val m = index.resolveMember(receiverType, seg)?.first ?: return null
            receiverType = if (m.kind == "method") m.returns else m.type
        }
        return index.getMemberPsiElements(project, receiverType, memberSegs.last())
    }

    fun resolveClSymbol(symbol: PsiElement, project: Project): PsiElement? {
        val (rawText, rawNamespace, rawName) = interopSymbolTextNamespaceName(symbol) ?: return null
        
        // Handle constructor call: (Fuse. ...)
        val isConstructor = rawText.endsWith(".") && rawText.length > 1 && !rawText.startsWith(".")
        val text = if (isConstructor) rawText.removeSuffix(".") else rawText
        val name = if (isConstructor && rawName != null) rawName.removeSuffix(".") else rawName
        val namespace = rawNamespace
        
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
                        val resolvedMember = index.resolveMember(parentType, last)
                            ?: return null
                        val member = resolvedMember.first ?: return null
                        val phys = firstMemberPsi(project, index, parentType, last)
                        JsSymbolPsiElement(
                            symbol.manager, symbol.language, last, resolvedMember.declaringType, member.doc,
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
                    val resolvedMember = index.resolveMember(typeName, memberName)
                    val member = resolvedMember?.first
                    if (member != null) {
                        val phys = firstMemberPsi(project, index, typeName, memberName)
                        JsSymbolPsiElement(
                            symbol.manager, symbol.language, memberName, resolvedMember.declaringType, member.doc,
                            member = member, navigationTarget = phys,
                        )
                    } else {
                        val targets = index.getAnyMemberPsiElements(project, memberName, typeName) ?: return null
                        val first = targets.firstOrNull() ?: return null
                        val ifaceName = (first as? JsMemberNavigationTarget)?.declaringTypeName
                        val memberDoc = ifaceName?.let { index.resolveMember(it, memberName)?.first?.doc }
                        JsSymbolPsiElement(
                            symbol.manager, symbol.language, memberName, ifaceName, memberDoc,
                            navigationTarget = first,
                        )
                    }
                } else {
                    val targets = index.getAnyMemberPsiElements(project, memberName, null) ?: return null
                    val first = targets.firstOrNull() ?: return null
                    val ifaceName = (first as? JsMemberNavigationTarget)?.declaringTypeName
                    val memberDoc = ifaceName?.let { index.resolveMember(it, memberName)?.first?.doc }
                    JsSymbolPsiElement(
                        symbol.manager, symbol.language, memberName, ifaceName, memberDoc,
                        navigationTarget = first,
                    )
                }
            }

            namespace != null -> {
                val file = symbol.containingFile ?: return null
                val aliases = NsAliasResolver.resolveAliases(file)
                val pkg = aliases[namespace] ?: return null
                val export = name ?: return null
                if (index.isKnownNpmExport(pkg, export)) {
                    val anchorPath = file.virtualFile?.path
                    val phys = firstNpmExportPsi(project, index, pkg, export)
                        ?: firstPsiFromPackageTypingsEntry(project, pkg, anchorPath)
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
                var exportKey = when {
                    index.isKnownNpmExport(pkg, "default") -> "default"
                    index.isKnownNpmExport(pkg, text) -> text
                    else -> index.npmExportNames(pkg).firstOrNull()
                }
                val anchorPath = file.virtualFile?.path
                var phys = exportKey?.let { firstNpmExportPsi(project, index, pkg, it) }
                if (phys == null) {
                    phys = firstPsiFromPackageTypingsEntry(project, pkg, anchorPath)
                    if (phys != null && exportKey == null) {
                        exportKey = "default"
                    }
                }
                if (phys == null) return null
                JsSymbolPsiElement(
                    symbol.manager, symbol.language, text, null, null,
                    packageName = pkg, npmExportName = exportKey ?: "default", navigationTarget = phys,
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
