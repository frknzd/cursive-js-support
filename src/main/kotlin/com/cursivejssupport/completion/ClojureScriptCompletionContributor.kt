package com.cursivejssupport.completion

import com.cursivejssupport.debug.InteropLogView
import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NpmPackageResolver
import com.cursivejssupport.npm.NsAliasResolver
import com.cursivejssupport.npm.NsRequireCompletionUtil
import com.cursivejssupport.util.InteropDebugLog
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
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
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
        if (typeChar == '/') {
            val doc = position.containingFile.viewProvider.document
            if (doc != null) {
                val offset = min(position.textOffset, doc.textLength)
                if (JsInteropCompletionPrefixes.shouldSuppressInvalidJsSlashAutoPopup(doc, offset)) {
                    InteropDebugLog.debug(
                        "[interop-completion] invokeAutoPopup: suppress '/' (invalid js/.../ tail)",
                    )
                    return false
                }
            }
            InteropDebugLog.debug(
                "[interop-completion] invokeAutoPopup file=${position.containingFile.name} typeChar=$typeChar",
            )
            return true
        }
        if (typeChar == '.') {
            InteropDebugLog.debug(
                "[interop-completion] invokeAutoPopup file=${position.containingFile.name} typeChar=$typeChar",
            )
            return true
        }
        if (typeChar == '-' && isLikelyInteropPropertyDash(position)) {
            InteropDebugLog.debug(
                "[interop-completion] invokeAutoPopup file=${position.containingFile.name} typeChar=$typeChar (dash member)",
            )
            return true
        }
        if (typeChar == '(') {
            val sym = JsInteropPsi.enclosingEditorSymbol(position)
            if (sym?.namespace == "js") {
                InteropDebugLog.debug(
                    "[interop-completion] invokeAutoPopup file=${position.containingFile.name} typeChar=( js head",
                )
                return true
            }
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

    companion object {
        private val completionInvocationSeq = AtomicLong(0)
    }

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val invokeId = completionInvocationSeq.incrementAndGet()
        val position = parameters.position
        val originalFile = parameters.originalFile
        InteropLogView.noteProject(originalFile.project)

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

        // CRITICAL FIX: Use parameters.originalFile!
        // The completion file is often a dummy copy that might have a broken PSI tree.
        val file = originalFile
        val project: Project = originalFile.project
        val requirePrefix = NsRequireCompletionUtil.npmPackagePrefixInRequire(file, parameters.offset)
        val anchorPath = file.virtualFile?.path

        val effForJsBranch = JsInteropCompletionPrefixes.effectiveJsInteropUserTextForJsBranch(
            parameters,
            position,
            userText,
            traceEffectiveJs = requirePrefix == null,
        )
        val effNorm = JsInteropCompletionPrefixes.normalizedEffectiveJsForCompletion(effForJsBranch)
        val eff = JsInteropCompletionPrefixes.collapseInvalidSlashesInJsInteropNormalizedPrefix(effNorm)
        val jsInteropLike =
            eff.startsWith("js/") ||
                JsInteropPsi.enclosingEditorSymbol(position)?.namespace == "js"

        val aliases = NsAliasResolver.resolveAliases(file)

        val index = JsSymbolIndex.getInstance()

        InteropDebugLog.info(
            "[interop-completion] >>> contributor=ClojureScriptCompletionContributor invokeId=$invokeId " +
                "offset=${parameters.offset} rawLeaf='$rawLeaf'",
        )
        InteropDebugLog.info(
            "[interop-completion] start file=${file.name} offset=${parameters.offset} anchor=${anchorPath ?: "null"} " +
                "indexLoaded=${index.isLoaded} userText='$userText' effRaw='$effForJsBranch' effNorm='$effNorm' eff='$eff' " +
                "jsInteropLike=$jsInteropLike requirePrefix=${requirePrefix?.let { "'$it'" } ?: "null"} " +
                "typedNs=$typedNamespace",
        )
        when {
            requirePrefix != null -> {
                val reqResult = result.withPrefixMatcher(CaseInsensitivePlainPrefixMatcher(requirePrefix))
                val pkgs = NpmPackageResolver(project).discoverAllDependencyPackageNames(anchorPath)
                for (pkg in pkgs) {
                    reqResult.addElement(
                        LookupElementBuilder.create(pkg)
                            .withPresentableText(pkg)
                            .withIcon(JsInteropCompletionIcons.forNpmNamespaceAlias())
                    )
                }
                InteropDebugLog.debug("[interop-completion] contributed requireNpm count=${pkgs.size}")
                InteropDebugLog.info(
                    "[interop-completion] branch=requireNpm prefix='$requirePrefix' packageSuggestions=${pkgs.size} " +
                        "anchor=${anchorPath ?: "null"}",
                )
            }

            jsInteropLike -> {
                if (!index.isLoaded) {
                    InteropDebugLog.info("[interop-completion] js branch: skipped (JsSymbolIndex not loaded)")
                    return
                }
                if (!eff.startsWith("js/")) {
                    InteropDebugLog.info(
                        "[interop-completion] js branch: skipped effRaw='$effForJsBranch' effNorm='$effNorm' eff='$eff' " +
                            "(expected js/ prefix; check document slice / PSI)",
                    )
                    return
                }
                val jsPm = JsInteropChainPrefixMatcher.fromEffectiveJs(eff)
                    ?: CaseInsensitivePlainPrefixMatcher(eff)
                val jsResult = result.withPrefixMatcher(jsPm)
                val pathRawBase = JsInteropChain.pathAfterJsPrefixRaw(eff) ?: run {
                    InteropDebugLog.info("[interop-completion] js branch: pathAfterJsPrefixRaw null for eff='$eff'")
                    return
                }
                val doc = parameters.editor.document
                val caret = min(parameters.offset, doc.textLength)
                val editorDot = caret > 0 && doc.charsSequence[caret - 1] == '.'
                val pathRaw = JsInteropChain.reconcileJsPathRawWithTrailingEditorDot(pathRawBase, editorDot)
                val pathTrimmed = pathRaw.trimEnd('.')
                val pathSegments = pathTrimmed.split('.').map { it.trim() }.filter { it.isNotEmpty() }
                val subBranch = classifyJsInteropJsCompletionSubBranch(pathRaw, pathTrimmed, index)
                val globalTypeForHead = pathSegments.singleOrNull()?.let { index.resolveGlobalType(it) }
                val callHead = isJsInteropHeadOfEnclosingList(position)
                val methodsOnly =
                    callHead &&
                        subBranch == JsInteropJsCompletionSubBranch.MembersAfterGlobal &&
                        globalTypeForHead != null
                InteropDebugLog.info(
                    "[interop-completion] js/route invokeId=$invokeId effRaw='$effForJsBranch' effNorm='$eff' " +
                        "pathRaw='$pathRaw' " +
                        "pathTrimmed='$pathTrimmed' segments=$pathSegments subBranch=$subBranch " +
                        "globalType=${globalTypeForHead ?: "null"} callHead=$callHead methodsOnly=$methodsOnly " +
                        "jsPm=${jsPm::class.java.simpleName} pmPrefix='${jsPm.prefix}'",
                )
                InteropDebugLog.debug(
                    "[interop-completion] js branch detail: effNorm='$eff' pathRaw='$pathRaw' pathTrimmed='$pathTrimmed'",
                )
                when (subBranch) {
                    JsInteropJsCompletionSubBranch.MembersAfterGlobal -> {
                        val n = addJsMembersAfterGlobal(
                            pathTrimmed,
                            index,
                            jsResult,
                            memberKinds = if (methodsOnly) setOf("method") else null,
                        )
                        InteropDebugLog.info(
                            "[interop-completion] contributed jsMembersAfterGlobal invokeId=$invokeId count=$n " +
                                "global='$pathTrimmed' methodsOnly=$methodsOnly",
                        )
                    }
                    JsInteropJsCompletionSubBranch.ChainMembers -> {
                        val n = addJsChainMemberCompletions(eff, index, jsResult)
                        InteropDebugLog.info(
                            "[interop-completion] contributed jsChainMembers invokeId=$invokeId count=$n pathRaw='$pathRaw'",
                        )
                    }
                    JsInteropJsCompletionSubBranch.GlobalsFiltered -> {
                        val n = addGlobalsFiltered(index, jsResult, pathTrimmed)
                        InteropDebugLog.info(
                            "[interop-completion] contributed jsGlobalsFiltered invokeId=$invokeId count=$n prefix='$pathTrimmed'",
                        )
                    }
                }
                InteropDebugLog.info(
                    "[interop-completion] branch=jsInterop invokeId=$invokeId subBranch=$subBranch effNorm='$eff' pathRaw='$pathRaw'",
                )
            }

            userText.startsWith(".") -> {
                if (!index.isLoaded) {
                    InteropDebugLog.info("[interop-completion] branch=memberDot skipped (JsSymbolIndex not loaded)")
                } else {
                    // Walk up the PSI tree to find the enclosing list `(...)`
                    var current: PsiElement? = position
                    while (current != null && !current.text.startsWith("(")) {
                        current = current.parent
                    }
                    if (current != null) {
                        val n = addMemberCompletions(current, index, safeResult)
                        InteropDebugLog.debug("[interop-completion] contributed memberDot count=$n")
                    }
                    InteropDebugLog.info(
                        "[interop-completion] branch=memberDot enclosingList=${current != null}",
                    )
                }
            }

            typedNamespace != null && aliases.containsKey(typedNamespace) -> {
                if (!index.isLoaded) {
                    InteropDebugLog.info("[interop-completion] branch=npmTypedNs skipped (JsSymbolIndex not loaded)")
                } else {
                    val pkg = aliases[typedNamespace]!!
                    val stripped = stripCompletionDummy(userText)
                    val prefix = "$typedNamespace/"
                    val rel = npmAliasRelativeTail(typedNamespace, prefix, stripped, position)
                    val exportKey = npmAliasExportKeySegment(rel)
                    val exportCount = index.npmExportNames(pkg).size
                    val memberMode = npmAliasWantsMemberCompletion(rel, exportKey, userText, index, pkg)
                    val n = if (memberMode) {
                        addNpmAliasExportMemberCompletions(typedNamespace, pkg, exportKey, rel, index, safeResult)
                    } else {
                        addNpmExports(typedNamespace, pkg, index, safeResult)
                    }
                    InteropDebugLog.debug("[interop-completion] contributed npmTypedNs count=$n")
                    InteropDebugLog.info(
                        "[interop-completion] branch=npmTypedNs ns=$typedNamespace pkg=$pkg exportSuggestions=$exportCount " +
                            "memberMode=$memberMode",
                    )
                }
            }

            true -> {
                var contributed = 0
                if (userText == "js") {
                    safeResult.addElement(
                        LookupElementBuilder.create("js/")
                            .withIcon(JsInteropCompletionIcons.forJsInteropRoot()),
                    )
                    contributed++
                }

                if (index.isLoaded && aliases.containsKey(userText)) {
                    contributed += addNpmExports(userText, aliases[userText]!!, index, safeResult)
                }

                for ((aliasName, pkgName) in aliases) {
                    safeResult.addElement(
                        LookupElementBuilder.create(aliasName)
                            .withTypeText(pkgName)
                            .withIcon(JsInteropCompletionIcons.forNpmNamespaceAlias())
                    )
                    contributed++
                }
                InteropDebugLog.debug("[interop-completion] contributed generalBranch count=$contributed")
                InteropDebugLog.info(
                    "[interop-completion] branch=general userText='$userText' indexLoaded=${index.isLoaded} " +
                        "addedJsSlash=${userText == "js"} npmExportsForBareUserText=" +
                        "${index.isLoaded && aliases.containsKey(userText)} aliasLookupRows=${aliases.size}",
                )
            }
        }
    }

    private fun npmAliasRelativeTail(ns: String, prefix: String, stripped: String, position: PsiElement): String {
        val raw = when {
            stripped.startsWith(prefix) -> stripped.removePrefix(prefix)
            else -> {
                val sym = JsInteropPsi.enclosingEditorSymbol(position)
                if (sym?.namespace == ns && !sym.name.isNullOrBlank()) sym.name!!.trim()
                else if ('/' in stripped) stripped.substringAfter('/')
                else stripped
            }
        }
        return stripCompletionDummy(raw).trim()
    }

    private fun npmAliasExportKeySegment(rel: String): String =
        stripCompletionDummy(rel.substringBefore('.'))
            .substringBefore('/')
            .trim()

    private fun npmAliasWantsMemberCompletion(
        rel: String,
        exportKey: String,
        userText: String,
        index: JsSymbolIndex,
        packageName: String,
    ): Boolean =
        index.isKnownNpmExport(packageName, exportKey) && (rel.contains('.') || userText.endsWith('.'))

    private fun addNpmAliasExportMemberCompletions(
        typedNamespace: String,
        packageName: String,
        exportKey: String,
        rel: String,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        val afterExport = if (rel.startsWith(exportKey)) rel.drop(exportKey.length).trimStart('.') else rel
        val rest = afterExport
        val restTrimmed = rest.trimEnd('.')
        val allSegs = if (restTrimmed.isEmpty()) {
            emptyList()
        } else {
            restTrimmed.split('.')
                .map { stripCompletionDummy(it).substringBefore('/').trim() }
                .filter { it.isNotEmpty() }
        }
        val dottedRestEnds = rest.endsWith('.') || (rest.isEmpty() && rel.endsWith('.'))
        val receiverSegs = when {
            allSegs.isEmpty() -> emptyList()
            dottedRestEnds -> allSegs
            else -> allSegs.dropLast(1)
        }
        var receiverType = index.resolveNpmExportType(packageName, exportKey)
            ?: return addNpmExports(typedNamespace, packageName, index, result)
        for (seg in receiverSegs) {
            val ifaceStep = index.resolveInterface(receiverType)
                ?: return addNpmExports(typedNamespace, packageName, index, result)
            val m = ifaceStep.members[seg]?.firstOrNull()
                ?: return addNpmExports(typedNamespace, packageName, index, result)
            receiverType = if (m.kind == "method") m.returns else m.type
        }
        val finalIface = index.resolveInterface(receiverType)
            ?: return addNpmExports(typedNamespace, packageName, index, result)
        val chainBase = buildString {
            append(typedNamespace).append('/').append(exportKey)
            for (s in receiverSegs) {
                append('.').append(s)
            }
            append('.')
        }
        return emitMembersForJsChain(finalIface.members, chainBase, receiverType, result)
    }

    private fun addJsChainMemberCompletions(userText: String, index: JsSymbolIndex, result: CompletionResultSet): Int {
        val path = JsInteropChain.pathAfterJsPrefixTrimmed(userText) ?: run {
            InteropDebugLog.info("[interop-completion] addJsChainMemberCompletions: pathAfterJsPrefixTrimmed null userText='$userText'")
            return 0
        }
        if (path.isEmpty()) {
            InteropDebugLog.info("[interop-completion] addJsChainMemberCompletions: empty path userText='$userText'")
            return 0
        }
        val parts = path.split('.')
        if (parts.size < 2) {
            InteropDebugLog.info("[interop-completion] addJsChainMemberCompletions: parts.size=${parts.size} path='$path'")
            return 0
        }
        val receiverParts = parts.dropLast(1).map { it.trim() }.filter { it.isNotEmpty() }
        if (receiverParts.isEmpty()) {
            InteropDebugLog.info("[interop-completion] addJsChainMemberCompletions: empty receiverParts path='$path'")
            return 0
        }
        val receiverType = index.resolveJsChainType(receiverParts) ?: run {
            InteropDebugLog.info(
                "[interop-completion] addJsChainMemberCompletions: resolveJsChainType null receiver=$receiverParts",
            )
            return 0
        }
        val iface = index.resolveInterface(receiverType) ?: run {
            InteropDebugLog.info(
                "[interop-completion] addJsChainMemberCompletions: no interface for type='$receiverType'",
            )
            return 0
        }
        val memberCount = iface.members.size
        InteropDebugLog.debug(
            "[interop-completion] addJsChainMemberCompletions: receiverType=$receiverType members=$memberCount " +
                "resultPrefix='${result.prefixMatcher.prefix}'",
        )
        val chainBase = "js/" + receiverParts.joinToString(".") + "."
        return emitMembersForJsChain(iface.members, chainBase, receiverType, result)
    }

    private fun addJsMembersAfterGlobal(
        globalName: String,
        index: JsSymbolIndex,
        result: CompletionResultSet,
        memberKinds: Set<String>? = null,
    ): Int {
        val typeName = index.resolveGlobalType(globalName) ?: return 0
        val iface = index.resolveInterface(typeName) ?: return 0
        val chainBase = "js/$globalName."
        return emitMembersForJsChain(iface.members, chainBase, typeName, result, memberKinds = memberKinds)
    }

    /**
     * True when the caret sits under the first non-paren child of an enclosing list `(head ...)`,
     * i.e. the interop symbol is in "call head" position — prefer method members for `js/document.|`.
     */
    private fun isJsInteropHeadOfEnclosingList(position: PsiElement): Boolean {
        var list: PsiElement? = position
        while (list != null && !list.text.startsWith("(")) {
            list = list.parent
        }
        if (list == null) return false
        val children = list.children.filter {
            it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")"
        }
        val head = children.firstOrNull() ?: return false
        return PsiTreeUtil.isAncestor(head, position, false)
    }

    private fun stripCompletionDummy(s: String): String =
        s.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .trim()

    private fun addGlobalsFiltered(index: JsSymbolIndex, result: CompletionResultSet, namePrefix: String): Int {
        val p = stripCompletionDummy(namePrefix)
        var n = 0
        for (globalName in index.allGlobalNames()) {
            if (p.isNotEmpty() && !globalName.startsWith(p, ignoreCase = true)) continue
            val typeName = index.resolveGlobalType(globalName) ?: continue
            result.addElement(
                LookupElementBuilder.create("js/$globalName")
                    .withPresentableText(globalName)
                    .withTypeText(typeName)
                    .withIcon(JsInteropCompletionIcons.forGlobalVariable()),
            )
            n++
        }
        for (fnName in index.allFunctionNames()) {
            if (p.isNotEmpty() && !fnName.startsWith(p, ignoreCase = true)) continue
            result.addElement(
                LookupElementBuilder.create("js/$fnName")
                    .withPresentableText(fnName)
                    .withTypeText("function")
                    .withIcon(JsInteropCompletionIcons.forGlobalFunction()),
            )
            n++
        }
        return n
    }

    private fun emitMembersForJsChain(
        members: Map<String, List<com.cursivejssupport.parser.JsMember>>,
        chainBase: String,
        receiverTypeLabel: String,
        result: CompletionResultSet,
        memberKinds: Set<String>? = null,
    ): Int {
        var n = 0
        for ((memberName, overloads) in members) {
            val first = overloads.firstOrNull() ?: continue
            if (memberKinds != null && first.kind !in memberKinds) continue
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
                    n++
                }
                "property" -> {
                    result.addElement(
                        LookupElementBuilder.create(insert)
                            .withPresentableText(memberName)
                            .withTypeText(receiverTypeLabel)
                            .withIcon(JsInteropCompletionIcons.forJsMemberKind("property")),
                    )
                    n++
                }
            }
        }
        return n
    }

    private fun addMemberCompletions(
        listElement: PsiElement,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        val children = listElement.children.filter { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")" }
        val receiver = children.getOrNull(1)

        val typeName = JsResolveUtil.resolveType(receiver, index)

        return if (typeName != null) {
            val iface = index.resolveInterface(typeName) ?: return 0
            emitMembers(iface.members, typeLabel = null, result)
        } else {
            val pm = result.prefixMatcher.prefix
            val memberPrefix = pm.removePrefix(".-").removePrefix(".").trim()
            var n = 0
            if (index.isLoaded) {
                for ((memberName, typeLabel, first) in index.sampleMembersByNamePrefix(memberPrefix)) {
                    emitSingleMember(memberName, typeLabel, first, result)
                    n++
                }
            }
            n
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
        result: CompletionResultSet,
    ): Int {
        var n = 0
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
                    n++
                }
                "property" -> {
                    result.addElement(
                        LookupElementBuilder.create(".-$memberName")
                            .withPresentableText(".-$memberName")
                            .withTypeText(typeLabel ?: first.type)
                            .withIcon(JsInteropCompletionIcons.forJsMemberKind("property")),
                    )
                    n++
                }
            }
        }
        return n
    }

    private fun addNpmExports(
        namespace: String,
        packageName: String,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        var n = 0
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
            n++
        }
        return n
    }
}

/**
 * Routes `js/...` completion after [JsInteropChain.pathAfterJsPrefixRaw] / trimmed path.
 * `MembersAfterGlobal` is for `js/document.|` (trailing dot, single global segment with a known TS type).
 */
internal enum class JsInteropJsCompletionSubBranch {
    MembersAfterGlobal,
    ChainMembers,
    GlobalsFiltered,
}

internal fun classifyJsInteropJsCompletionSubBranch(
    pathRaw: String,
    pathTrimmed: String,
    index: JsSymbolIndex,
): JsInteropJsCompletionSubBranch {
    val segments = pathTrimmed.split('.').map { it.trim() }.filter { it.isNotEmpty() }
    if (pathRaw.endsWith('.') && segments.size == 1 && index.resolveGlobalType(segments[0]) != null) {
        return JsInteropJsCompletionSubBranch.MembersAfterGlobal
    }
    if (pathRaw.contains('.') || pathTrimmed.contains('.')) {
        return JsInteropJsCompletionSubBranch.ChainMembers
    }
    return JsInteropJsCompletionSubBranch.GlobalsFiltered
}