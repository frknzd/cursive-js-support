package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.IntellijNpmResolutionService
import com.cursivejssupport.npm.NpmPackageResolver
import com.cursivejssupport.npm.NsAliasResolver
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import cursive.psi.api.ClList
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
            val index = JsSymbolIndex.getInstance()
            val knownGoogNamespaces = if (index.isLoaded) index.getGoogNamespaceNames().toHashSet() else emptySet()

            val context = InteropContextDetector.detect(document.charsSequence, caret, aliases, knownGoogNamespaces)
            if (context is InteropCompletionContext.None) return
            
            // Set prefix matcher. If prefix is empty, we still want to match everything.
            val matcher = PlainPrefixMatcher(context.prefix, /*caseSensitive=*/false)
            val result = baseResult.withPrefixMatcher(matcher)

            // NsRequirePackage calls stopHere() inside emitNpmPackages; all other interop
            // contexts call stopHere() here once we know we produced at least one item, so
            // Cursive's generic <global> suggestions don't appear alongside ours.
            // GoogNamespaceName is intentionally excluded from stopHere() so Cursive's own
            // completions (e.g. core.async `go`, `go-loop`) still appear alongside goog ones.
            val stopHere: Boolean
            val n: Int = when (context) {
                is InteropCompletionContext.NsRequirePackage -> {
                    if (context.prefix.isEmpty()) result.restartCompletionOnAnyPrefixChange()
                    emitNpmPackages(context, file, parameters, result)
                    stopHere = false // emitNpmPackages handles stopHere internally
                    0
                }
                is InteropCompletionContext.DotMember -> {
                    stopHere = true
                    val list = enclosingClList(parameters.position)
                    InteropCompletionItems.emit(context, file, index, result, list)
                }
                is InteropCompletionContext.GoogNamespaceName -> {
                    stopHere = false
                    InteropCompletionItems.emit(context, file, index, result, null)
                }
                else -> {
                    stopHere = true
                    InteropCompletionItems.emit(context, file, index, result, null)
                }
            }
            if (n > 0 && stopHere) result.stopHere()
        }

        private fun emitNpmPackages(
            context: InteropCompletionContext.NsRequirePackage,
            file: PsiFile,
            parameters: CompletionParameters,
            result: CompletionResultSet,
        ) {
            val project = file.project
            val anchor = file.virtualFile?.path
            
            val resolver = project.service<NpmPackageResolver>()
            val manualPackages = resolver.discoverAllDependencyPackageNames(anchor)
            val service = project.serviceOrNull<IntellijNpmResolutionService>()
            val intellijPackages = service?.discoverPackages(file) ?: emptySet()
            
            val combined = mutableSetOf<String>()
            combined.addAll(manualPackages)
            combined.addAll(intellijPackages)

            if (context.prefix.isEmpty()) {
                result.restartCompletionOnAnyPrefixChange()
            }
            InteropCompletionItems.emitNpmPackages(combined, result)
        }

        private fun enclosingClList(element: PsiElement?): ClList? {
            if (element == null) return null
            return PsiTreeUtil.getParentOfType(element, ClList::class.java, false)
        }

        private fun isClojureDialectFile(file: PsiFile): Boolean {
            val n = file.name
            return n.endsWith(".cljs", ignoreCase = true) ||
                n.endsWith(".cljc", ignoreCase = true) ||
                n.endsWith(".clj", ignoreCase = true)
        }
    }
}
