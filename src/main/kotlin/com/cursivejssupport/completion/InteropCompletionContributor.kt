package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.semantic.InteropSemanticService
import com.cursivejssupport.npm.NsAliasResolver
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.openapi.components.service
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import kotlin.math.min

/**
 * The sole completion contributor for ClojureScript JS interop and shadow-cljs npm forms.
 *
 * Workflow (per [CompletionParameters]):
 * 1. Build an [InteropCompletionContext] from the *document* (not PSI) at the caret.
 * 2. Replace the result's [PrefixMatcher] with a short, accurate prefix.
 * 3. Dispatch to [InteropCompletionItems] which produces [LookupElement]s.
 *
 * Registered with `order="first"` in plugin.xml so it adds elements before Cursive's symbol
 * contributor (if any) calls [CompletionResultSet.stopHere].
 */
class InteropCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            Provider(),
        )
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!isClojureDialectFile(file)) {
            super.fillCompletionVariants(parameters, result)
            return
        }
        val caret = min(parameters.offset, parameters.editor.document.textLength)
        val index = JsSymbolIndex.getInstance(file.project)
        val aliases = NsAliasResolver.resolveAliases(file)
        val knownGoogNamespaces = if (index.isLoaded) index.getGoogNamespaceNames().toHashSet() else emptySet()
        val context = InteropContextDetector.detect(
            parameters.editor.document.charsSequence,
            caret,
            aliases,
            knownGoogNamespaces,
        )

        super.fillCompletionVariants(parameters, result)

        if (InteropCompletionAuthority.owns(context, index, parameters.position)) {
            // A proven JS receiver makes lexical vars, namespaces, and Cursive's global prototype
            // sample syntactically irrelevant. Our provider has already emitted the complete typed
            // member set; consuming the remainder removes noise without disabling Cursive in any
            // unresolved or ordinary Clojure completion context.
            result.stopHere()
        }
    }

    private class Provider : CompletionProvider<CompletionParameters>() {

        override fun addCompletions(
            parameters: CompletionParameters,
            processingContext: ProcessingContext,
            baseResult: CompletionResultSet,
        ) {
            val file = parameters.originalFile
            if (!isClojureDialectFile(file)) return

            val document = parameters.editor.document
            val caret = min(parameters.offset, document.textLength)
            val aliases = NsAliasResolver.resolveAliases(file)
            val index = JsSymbolIndex.getInstance(file.project)
            val knownGoogNamespaces = if (index.isLoaded) index.getGoogNamespaceNames().toHashSet() else emptySet()

            val context = InteropContextDetector.detect(document.charsSequence, caret, aliases, knownGoogNamespaces)
            if (context is InteropCompletionContext.None) return

            val matcher = PlainPrefixMatcher(context.prefix, false)
            val result = baseResult.withPrefixMatcher(matcher)

            if (context is InteropCompletionContext.NsRequirePackage) {
                emitNpmPackages(context, file, result)
                return
            }

            if (context is InteropCompletionContext.NsRequireRelativePackage) {
                emitRelativeFiles(context, file, result)
                return
            }

            InteropCompletionItems.emit(context, file, index, result, parameters.position)
        }

    private fun emitNpmPackages(
            context: InteropCompletionContext.NsRequirePackage,
            file: PsiFile,
            result: CompletionResultSet,
        ) {
            val project = file.project
            if (context.prefix.isEmpty()) {
                result.restartCompletionOnAnyPrefixChange()
            }
            InteropCompletionItems.emitNpmPackages(project.service<InteropSemanticService>().packages(file), result)
        }

    private fun emitRelativeFiles(
            context: InteropCompletionContext.NsRequireRelativePackage,
            file: PsiFile,
            result: CompletionResultSet,
        ) {
            if (context.prefix.isEmpty()) {
                result.restartCompletionOnAnyPrefixChange()
            }
            InteropCompletionItems.emitRelativeRequireFiles(file, context.prefix, result)
        }

    }

    companion object {
        private fun isClojureDialectFile(file: PsiFile): Boolean {
            val n = file.name
            return n.endsWith(".cljs", ignoreCase = true) ||
                n.endsWith(".cljc", ignoreCase = true) ||
                n.endsWith(".clj", ignoreCase = true)
        }
    }
}
