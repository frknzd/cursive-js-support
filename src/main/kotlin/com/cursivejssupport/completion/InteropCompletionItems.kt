package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.util.JsResolveUtil
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import cursive.psi.api.ClList

/**
 * Builds [LookupElement]s for a given [InteropCompletionContext].
 *
 * Lookup strings are always the *short* name (member name, package name, export name). The
 * platform's [com.intellij.codeInsight.completion.PrefixMatcher] takes care of in-place
 * replacement backward by the context's prefix length, so the receiver / leading punctuation
 * the user already typed stays intact.
 *
 * Method elements use a small [InsertionContext]-aware handler that inserts `(` and a closing
 * `)` if the user is in head position and the lookup is a function/method.
 */
import com.cursivejssupport.npm.IntellijNpmResolutionService
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.lang.javascript.psi.JSNamedElement

object InteropCompletionItems {

    fun emit(
        context: InteropCompletionContext,
        file: PsiFile,
        index: JsSymbolIndex,
        result: CompletionResultSet,
        listAroundCaret: PsiElement?,
    ): Int = when (context) {
        is InteropCompletionContext.None -> 0
        is InteropCompletionContext.JsGlobalName -> emitJsGlobals(index, result)
        is InteropCompletionContext.JsChainMember -> emitJsChainMembers(context, index, result)
        is InteropCompletionContext.DotMember -> emitDotMembers(context, index, result, listAroundCaret)
        is InteropCompletionContext.NsRequirePackage -> 0 // handled by contributor (needs Project)
        is InteropCompletionContext.NsRequireKeyword -> emitRequireKeywords(context, result)
        is InteropCompletionContext.NsRefer -> emitRefer(context, file, index, result)
        is InteropCompletionContext.NpmAliasName -> emitNpmAliasNames(context, result)
        is InteropCompletionContext.NpmAliasExport -> emitNpmAliasExports(context, file, index, result)
        is InteropCompletionContext.NpmAliasExportMember -> emitNpmAliasExportMembers(context, file, index, result)
    }

    private fun emitNpmAliasNames(context: InteropCompletionContext.NpmAliasName, result: CompletionResultSet): Int {
        var n = 0
        for ((alias, binding) in context.availableAliases) {
            result.addElement(npmAliasNameLookup(alias, binding.packageName))
            n++
        }
        return n
    }

    /**
     * Public entry for the NpmRequirePackage slot — the contributor discovers the iterable
     * of package names (it needs the [com.intellij.openapi.project.Project] / resolver) and
     * delegates the lookup-element construction here.
     */
    fun emitNpmPackages(packages: Iterable<String>, result: CompletionResultSet): Int {
        var n = 0
        for (name in packages) {
            result.addElement(npmPackageLookup(name))
            n++
        }
        if (n > 0) result.stopHere()
        return n
    }

    // ─── Producers ──────────────────────────────────────────────────────────

    private fun emitJsGlobals(index: JsSymbolIndex, result: CompletionResultSet): Int {
        if (!index.isLoaded) return 0
        var n = 0
        for (name in index.allGlobalNames()) {
            val type = index.resolveGlobalType(name) ?: continue
            result.addElement(globalVariableLookup(name, type))
            n++
        }
        for (name in index.allFunctionNames()) {
            result.addElement(globalFunctionLookup(name))
            n++
        }
        return n
    }

    private fun emitJsChainMembers(
        context: InteropCompletionContext.JsChainMember,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        if (!index.isLoaded) return 0
        val receiverType = index.resolveJsChainType(context.receiverSegments) ?: return 0
        return emitMembers(receiverType, index, result, asProperty = null)
    }

    private fun emitDotMembers(
        context: InteropCompletionContext.DotMember,
        index: JsSymbolIndex,
        result: CompletionResultSet,
        listAroundCaret: PsiElement?,
    ): Int {
        if (!index.isLoaded) return 0
        val receiver = receiverForDotMember(listAroundCaret)
        val receiverType = if (receiver != null) JsResolveUtil.resolveType(receiver, index) else null
        if (receiverType != null) {
            return emitMembers(receiverType, index, result, asProperty = context.asProperty, dotForm = true)
        }
        // Receiver unknown — sample by member-name prefix.
        var n = 0
        for ((memberName, declaringType, member) in index.sampleMembersByNamePrefix(context.prefix)) {
            if (context.asProperty && member.kind != "property") continue
            if (!context.asProperty && member.kind != "method") continue
            result.addElement(memberLookup(memberName, declaringType, member, dotForm = true))
            n++
        }
        return n
    }

    private fun emitMembers(
        receiverType: String,
        index: JsSymbolIndex,
        result: CompletionResultSet,
        asProperty: Boolean?,
        dotForm: Boolean = false,
    ): Int {
        val members = index.resolveMembers(receiverType)
        var n = 0
        for ((memberName, resolved) in members) {
            val first = resolved.overloads.firstOrNull() ?: continue
            if (asProperty != null) {
                val want = if (asProperty) "property" else "method"
                if (first.kind != want) continue
            } else {
                if (first.kind != "method" && first.kind != "property") continue
            }
            result.addElement(memberLookup(memberName, resolved.declaringType, first, dotForm))
            n++
        }
        return n
    }

    private fun emitRequireKeywords(
        context: InteropCompletionContext.NsRequireKeyword,
        result: CompletionResultSet,
    ): Int {
        var n = 0
        for (kw in context.availableKeywords) {
            result.addElement(keywordLookup(kw))
            n++
        }
        return n
    }

    private fun emitRefer(
        context: InteropCompletionContext.NsRefer,
        file: PsiFile,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        var n = 0
        val service = file.project.serviceOrNull<IntellijNpmResolutionService>()
        if (service != null) {
            val exports = service.resolveExports(file, context.packageName)
            if (exports.isNotEmpty()) {
                for (export in exports) {
                    val name = (export as? JSNamedElement)?.name ?: continue
                    result.addElement(npmExportLookup(name, context.packageName))
                    n++
                }
                if (n > 0) return n
            }
        }

        // Only the package's named exports are valid here. Keyword helpers (`:as`, `:refer`,
        // `:rename`, `:default`) belong outside the `:refer` vector and surface through the
        // NsRequireKeyword slot instead.
        for (exportName in index.npmExportNames(context.packageName)) {
            result.addElement(npmExportLookup(exportName, context.packageName))
            n++
        }
        return n
    }

    private fun emitNpmAliasExports(
        context: InteropCompletionContext.NpmAliasExport,
        file: PsiFile,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        var n = 0
        val service = file.project.serviceOrNull<IntellijNpmResolutionService>()
        if (service != null) {
            val exports = service.resolveExports(file, context.packageName)
            if (exports.isNotEmpty()) {
                for (export in exports) {
                    val name = (export as? JSNamedElement)?.name ?: continue
                    result.addElement(npmExportLookup(name, context.packageName))
                    n++
                }
                if (n > 0) return n
            }
        }

        if (!index.isLoaded) return 0
        for (exportName in index.npmExportNames(context.packageName)) {
            result.addElement(npmExportLookup(exportName, context.packageName))
            n++
        }
        return n
    }

    private fun emitNpmAliasExportMembers(
        context: InteropCompletionContext.NpmAliasExportMember,
        file: PsiFile,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        var n = 0
        val service = file.project.serviceOrNull<IntellijNpmResolutionService>()
        if (service != null) {
            val exports = service.resolveExports(file, context.packageName)
            if (exports.isNotEmpty()) {
                // Here we would ideally evaluate the JSType of the specific export and its members.
                // For now, if IntelliJ resolves it, we can fallback to the index if we don't have JS type eval here,
                // or just skip because we only have PsiElement for the exports, not their deeply nested types.
            }
        }

        if (!index.isLoaded) return 0
        var receiverType = index.resolveNpmExportType(context.packageName, context.exportName) ?: return 0
        for (segment in context.receiverSegments) {
            val member = index.resolveMember(receiverType, segment)?.first ?: return 0
            receiverType = if (member.kind == "method") member.returns else member.type
        }
        return emitMembers(receiverType, index, result, asProperty = null)
    }

    // ─── Lookup builders ────────────────────────────────────────────────────

    private fun globalVariableLookup(name: String, type: String): LookupElement =
        LookupElementBuilder.create(name)
            .withPresentableText(name)
            .withTypeText(type)
            .withIcon(JsInteropCompletionIcons.forGlobalVariable())

    private fun globalFunctionLookup(name: String): LookupElement =
        LookupElementBuilder.create(name)
            .withPresentableText(name)
            .withTypeText("function")
            .withIcon(JsInteropCompletionIcons.forGlobalFunction())

    private fun memberLookup(memberName: String, declaringType: String?, member: JsMember, dotForm: Boolean): LookupElement {
        val sig = if (member.kind == "method") {
            "(" + member.params.joinToString(", ") { p ->
                when {
                    p.rest -> "...${p.name}: ${p.type}"
                    p.optional -> "${p.name}?: ${p.type}"
                    else -> "${p.name}: ${p.type}"
                }
            } + ")"
        } else null
        val presentable = if (dotForm) (if (member.kind == "property") ".-$memberName" else ".$memberName") else memberName
        val typeText = declaringType ?: if (member.kind == "method") member.returns else member.type
        var builder = LookupElementBuilder.create(memberName)
            .withPresentableText(presentable)
            .withTypeText(typeText)
            .withIcon(JsInteropCompletionIcons.forJsMemberKind(member.kind))
        if (sig != null) builder = builder.withTailText(sig, true)
        if (presentable != memberName) builder = builder.withLookupString(presentable)
        return builder
    }

    private fun npmExportLookup(exportName: String, packageName: String): LookupElement {
        val icon = if (exportName == "default") {
            JsInteropCompletionIcons.forNpmDefaultExport()
        } else {
            JsInteropCompletionIcons.forNpmNamedExport()
        }
        return LookupElementBuilder.create(exportName)
            .withPresentableText(exportName)
            .withTypeText(packageName)
            .withIcon(icon)
    }

    private fun npmPackageLookup(packageName: String): LookupElement =
        LookupElementBuilder.create(packageName)
            .withPresentableText(packageName)
            .withTypeText("npm")
            .withIcon(JsInteropCompletionIcons.forNpmNamespaceAlias())

    private fun npmAliasNameLookup(alias: String, packageName: String): LookupElement =
        LookupElementBuilder.create(alias)
            .withPresentableText(alias)
            .withTypeText(packageName)
            .withIcon(JsInteropCompletionIcons.forNpmNamespaceAlias())

    private fun keywordLookup(keyword: String): LookupElement =
        LookupElementBuilder.create(keyword)
            .withPresentableText(keyword)
            .withTypeText("ns")
            .withIcon(JsInteropCompletionIcons.forJsInteropRoot())
            .withInsertHandler(KeywordInsertHandler)

    // ─── PSI helpers ────────────────────────────────────────────────────────

    /**
     * Returns the second child of the enclosing list (the receiver of `(.method receiver ...)`).
     * Walks past whitespace / comments and the open paren.
     */
    private fun receiverForDotMember(listAroundCaret: PsiElement?): PsiElement? {
        val list = listAroundCaret as? ClList ?: return null
        val children = list.children.filter {
            it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")"
        }
        return children.getOrNull(1)
    }

    private object KeywordInsertHandler : com.intellij.codeInsight.completion.InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            // Add a trailing space so the user can keep typing the alias / refer collection.
            val editor = context.editor
            val doc = editor.document
            val offset = context.tailOffset
            if (offset >= doc.textLength || doc.charsSequence[offset] != ' ') {
                doc.insertString(offset, " ")
                editor.caretModel.moveToOffset(offset + 1)
            }
        }
    }
}
