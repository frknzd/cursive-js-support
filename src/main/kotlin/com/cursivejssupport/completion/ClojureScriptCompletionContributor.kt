package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NpmPackageResolver
import com.cursivejssupport.npm.NsAliasResolver
import com.cursivejssupport.npm.NsRequireCompletionUtil
import com.cursivejssupport.util.JsInteropChain
import com.cursivejssupport.util.JsInteropPsi
import com.cursivejssupport.util.JsResolveUtil
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.util.ProcessingContext
import kotlin.math.min

class ClojureScriptCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            ClojureScriptCompletionProvider()
        )
    }

    @Deprecated("Deprecated in Java")
    override fun invokeAutoPopup(position: PsiElement, typeChar: Char): Boolean {
        if (typeChar == '/' || typeChar == '.') return true
        if (typeChar == '-' && isLikelyInteropPropertyDash(position)) return true
        if (typeChar == '(') {
            val sym = JsInteropPsi.enclosingEditorSymbol(position)
            if (sym?.namespace == "js") return true
        }
        return super.invokeAutoPopup(position, typeChar)
    }
}

/** When PSI omits a trailing `.` (common after `js/document.`), align prefix with the document. */
private fun completionUserTextWithTerminalDot(
    parameters: CompletionParameters,
    position: PsiElement,
    rawLeaf: String,
): String {
    var base = JsInteropPsi.completionLogicalText(position, rawLeaf)
    val doc = parameters.editor.document
    val offset = min(parameters.offset, doc.textLength)
    if (offset > 0 && doc.charsSequence[offset - 1] == '.' && !base.endsWith(".")) {
        base += "."
    }
    return base
}

private fun isLikelyInteropPropertyDash(position: PsiElement): Boolean {
    val doc = position.containingFile.viewProvider.document ?: return false
    val offset = position.textOffset
    if (offset <= 0) return false
    if (doc.charsSequence[offset - 1] != '.') return false
    if (offset >= 2 && doc.charsSequence[offset - 2] == '.') return false
    val name = position.containingFile.name
    if (!name.endsWith(".cljs") && !name.endsWith(".cljc") && !name.endsWith(".clj")) return false
    val logical = JsInteropPsi.completionLogicalText(position, position.text.trim())
    return logical.startsWith(".") || logical.startsWith("js/") || logical.contains("js/")
}

private class ClojureScriptCompletionProvider : CompletionProvider<CompletionParameters>() {
    private val log = Logger.getInstance(ClojureScriptCompletionProvider::class.java)

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val position = parameters.position

        val rawLeaf = position.text
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .trim()

        val userText = completionUserTextWithTerminalDot(parameters, position, rawLeaf)

        val safeResult = result.withPrefixMatcher(CaseInsensitivePlainPrefixMatcher(userText))

        val typedNamespace = if (userText.contains("/")) {
            userText.substringBefore("/")
        } else {
            null
        }

        val effForJsBranch = JsInteropCompletionPrefixes.effectiveJsInteropUserTextForJsBranch(parameters, position, userText)
        val jsInteropLike =
            effForJsBranch.startsWith("js/") ||
                JsInteropPsi.enclosingEditorSymbol(position)?.namespace == "js"

        // CRITICAL FIX: Use parameters.originalFile!
        // The completion file is often a dummy copy that might have a broken PSI tree.
        val file = parameters.originalFile
        val project: Project = parameters.originalFile.project
        val aliases = NsAliasResolver.resolveAliases(file)

        val index = JsSymbolIndex.getInstance()

        val requirePrefix = NsRequireCompletionUtil.npmPackagePrefixInRequire(file, parameters.offset)

        when {
            requirePrefix != null -> {
                val reqResult = result.withPrefixMatcher(CaseInsensitivePlainPrefixMatcher(requirePrefix))
                val pkgs = NpmPackageResolver(project).discoverAllDependencyPackageNames()
                for (pkg in pkgs) {
                    reqResult.addElement(
                        LookupElementBuilder.create(pkg)
                            .withPresentableText(pkg)
                            .withIcon(JsInteropCompletionIcons.forNpmNamespaceAlias())
                    )
                }
            }

            jsInteropLike -> {
                if (!index.isLoaded) return
                val eff = effForJsBranch
                if (!eff.startsWith("js/")) return
                val jsPm = JsInteropChainPrefixMatcher.fromEffectiveJs(eff)
                    ?: CaseInsensitivePlainPrefixMatcher(eff)
                val jsResult = result.withPrefixMatcher(jsPm)
                val pathRaw = JsInteropChain.pathAfterJsPrefixRaw(eff) ?: return
                val pathTrimmed = pathRaw.trimEnd('.')
                when {
                    pathRaw.contains('.') || pathTrimmed.contains('.') ->
                        addJsChainMemberCompletions(eff, index, jsResult)
                    pathRaw.endsWith(".") && pathTrimmed.isNotEmpty() &&
                        index.resolveGlobalType(pathTrimmed) != null ->
                        addJsMembersAfterGlobal(pathTrimmed, index, jsResult)
                    else ->
                        addGlobalsFiltered(index, jsResult, pathTrimmed)
                }
            }

            userText.startsWith(".") -> {
                if (index.isLoaded) {
                    // Walk up the PSI tree to find the enclosing list `(...)`
                    var current: PsiElement? = position
                    while (current != null && !current.text.startsWith("(")) {
                        current = current.parent
                    }
                    if (current != null) {
                        addMemberCompletions(current, index, safeResult)
                    }
                }
            }

            typedNamespace != null && aliases.containsKey(typedNamespace) -> {
                if (index.isLoaded) {
                    val pkg = aliases[typedNamespace]!!
                    addNpmExports(typedNamespace, pkg, index, safeResult)
                }
            }

            true -> {
                if (userText == "js") {
                    safeResult.addElement(
                        LookupElementBuilder.create("js/")
                            .withIcon(JsInteropCompletionIcons.forJsInteropRoot()),
                    )
                }

                if (index.isLoaded && aliases.containsKey(userText)) {
                    addNpmExports(userText, aliases[userText]!!, index, safeResult)
                }

                for ((aliasName, pkgName) in aliases) {
                    safeResult.addElement(
                        LookupElementBuilder.create(aliasName)
                            .withTypeText(pkgName)
                            .withIcon(JsInteropCompletionIcons.forNpmNamespaceAlias())
                    )
                }
            }
        }
    }

    private fun addJsChainMemberCompletions(userText: String, index: JsSymbolIndex, result: CompletionResultSet) {
        val path = JsInteropChain.pathAfterJsPrefixTrimmed(userText) ?: return
        if (path.isEmpty()) return
        val parts = path.split('.')
        if (parts.size < 2) return
        val receiverParts = parts.dropLast(1).map { it.trim() }.filter { it.isNotEmpty() }
        if (receiverParts.isEmpty()) return
        val receiverType = index.resolveJsChainType(receiverParts) ?: return
        val iface = index.resolveInterface(receiverType) ?: return
        val chainBase = "js/" + receiverParts.joinToString(".") + "."
        emitMembersForJsChain(iface.members, chainBase, receiverType, result)
    }

    private fun addJsMembersAfterGlobal(globalName: String, index: JsSymbolIndex, result: CompletionResultSet) {
        val typeName = index.resolveGlobalType(globalName) ?: return
        val iface = index.resolveInterface(typeName) ?: return
        val chainBase = "js/$globalName."
        emitMembersForJsChain(iface.members, chainBase, typeName, result)
    }

    private fun stripCompletionDummy(s: String): String =
        s.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .trim()

    private fun addGlobalsFiltered(index: JsSymbolIndex, result: CompletionResultSet, namePrefix: String) {
        val p = stripCompletionDummy(namePrefix)
        for (globalName in index.allGlobalNames()) {
            if (p.isNotEmpty() && !globalName.startsWith(p, ignoreCase = true)) continue
            val typeName = index.resolveGlobalType(globalName) ?: continue
            result.addElement(
                LookupElementBuilder.create("js/$globalName")
                    .withPresentableText(globalName)
                    .withTypeText(typeName)
                    .withIcon(JsInteropCompletionIcons.forGlobalVariable()),
            )
        }
        for (fnName in index.allFunctionNames()) {
            if (p.isNotEmpty() && !fnName.startsWith(p, ignoreCase = true)) continue
            result.addElement(
                LookupElementBuilder.create("js/$fnName")
                    .withPresentableText(fnName)
                    .withTypeText("function")
                    .withIcon(JsInteropCompletionIcons.forGlobalFunction()),
            )
        }
    }

    private fun emitMembersForJsChain(
        members: Map<String, List<com.cursivejssupport.parser.JsMember>>,
        chainBase: String,
        receiverTypeLabel: String,
        result: CompletionResultSet
    ) {
        for ((memberName, overloads) in members) {
            val first = overloads.firstOrNull() ?: continue
            val insert = chainBase + memberName
            when (first.kind) {
                "method" -> {
                    val sig = first.params.joinToString(", ") { p ->
                        if (p.rest) "...${p.name}: ${p.type}"
                        else if (p.optional) "${p.name}?: ${p.type}"
                        else "${p.name}: ${p.type}"
                    }
                    result.addElement(
                        LookupElementBuilder.create(insert)
                            .withPresentableText(memberName)
                            .withTypeText(receiverTypeLabel)
                            .withTailText("($sig)", true)
                            .withIcon(JsInteropCompletionIcons.forJsMemberKind("method")),
                    )
                }
                "property" -> {
                    result.addElement(
                        LookupElementBuilder.create(insert)
                            .withPresentableText(memberName)
                            .withTypeText(receiverTypeLabel)
                            .withIcon(JsInteropCompletionIcons.forJsMemberKind("property")),
                    )
                }
            }
        }
    }

    private fun addMemberCompletions(
        listElement: PsiElement,
        index: JsSymbolIndex,
        result: CompletionResultSet
    ) {
        val children = listElement.children.filter { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")" }
        val receiver = children.getOrNull(1)

        val typeName = JsResolveUtil.resolveType(receiver, index)

        if (typeName != null) {
            val iface = index.resolveInterface(typeName) ?: return
            emitMembers(iface.members, typeLabel = null, result)
        } else {
            val pm = result.prefixMatcher.prefix
            val memberPrefix = pm.removePrefix(".-").removePrefix(".").trim()
            if (index.isLoaded) {
                for ((memberName, typeLabel, first) in index.sampleMembersByNamePrefix(memberPrefix)) {
                    emitSingleMember(memberName, typeLabel, first, result)
                }
            }
        }
    }

    private fun emitSingleMember(
        memberName: String,
        typeLabel: String,
        first: com.cursivejssupport.parser.JsMember,
        result: CompletionResultSet
    ) {
        when (first.kind) {
            "method" -> {
                val sig = first.params.joinToString(", ") { p ->
                    if (p.rest) "...${p.name}: ${p.type}"
                    else if (p.optional) "${p.name}?: ${p.type}"
                    else "${p.name}: ${p.type}"
                }
                result.addElement(
                    LookupElementBuilder.create(".$memberName")
                        .withPresentableText(".$memberName")
                        .withTypeText(typeLabel)
                        .withTailText("($sig)", true)
                        .withIcon(JsInteropCompletionIcons.forJsMemberKind("method")),
                )
            }
            "property" -> {
                result.addElement(
                    LookupElementBuilder.create(".-$memberName")
                        .withPresentableText(".-$memberName")
                        .withTypeText(typeLabel)
                        .withIcon(JsInteropCompletionIcons.forJsMemberKind("property")),
                )
            }
        }
    }

    private fun emitMembers(
        members: Map<String, List<com.cursivejssupport.parser.JsMember>>,
        typeLabel: String?,
        result: CompletionResultSet
    ) {
        for ((memberName, overloads) in members) {
            val first = overloads.firstOrNull() ?: continue
            when (first.kind) {
                "method" -> {
                    val sig = first.params.joinToString(", ") { p ->
                        if (p.rest) "...${p.name}: ${p.type}"
                        else if (p.optional) "${p.name}?: ${p.type}"
                        else "${p.name}: ${p.type}"
                    }
                    result.addElement(
                        LookupElementBuilder.create(".$memberName")
                            .withPresentableText(".$memberName")
                            .withTypeText(typeLabel ?: first.returns)
                            .withTailText("($sig)", true)
                            .withIcon(JsInteropCompletionIcons.forJsMemberKind("method")),
                    )
                }
                "property" -> {
                    result.addElement(
                        LookupElementBuilder.create(".-$memberName")
                            .withPresentableText(".-$memberName")
                            .withTypeText(typeLabel ?: first.type)
                            .withIcon(JsInteropCompletionIcons.forJsMemberKind("property")),
                    )
                }
            }
        }
    }

    private fun addNpmExports(
        namespace: String,
        packageName: String,
        index: JsSymbolIndex,
        result: CompletionResultSet
    ) {
        for (exportName in index.npmExportNames(packageName)) {
            val icon = if (exportName == "default") {
                JsInteropCompletionIcons.forNpmDefaultExport()
            } else {
                JsInteropCompletionIcons.forNpmNamedExport()
            }
            result.addElement(
                LookupElementBuilder.create("$namespace/$exportName")
                    .withPresentableText(exportName)
                    .withTypeText(packageName)
                    .withIcon(icon),
            )
        }
    }
}