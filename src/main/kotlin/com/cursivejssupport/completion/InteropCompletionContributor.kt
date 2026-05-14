package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NpmPackageResolver
import com.cursivejssupport.npm.NsAliasResolver
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PlainPrefixMatcher
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

            val context = InteropContextDetector.detect(document.charsSequence, caret, aliases)
            if (context is InteropCompletionContext.None) return

            val index = JsSymbolIndex.getInstance()
            val result = baseResult.withPrefixMatcher(PlainPrefixMatcher(context.prefix, /*caseSensitive=*/false))

            when (context) {
                is InteropCompletionContext.NsRequirePackage -> emitNpmPackages(context, file, parameters, result)
                is InteropCompletionContext.DotMember -> {
                    val list = enclosingClList(parameters.position)
                    InteropCompletionItems.emit(context, index, result, list)
                }
                else -> InteropCompletionItems.emit(context, index, result, null)
            }
        }

        private fun emitNpmPackages(
            context: InteropCompletionContext.NsRequirePackage,
            file: PsiFile,
            parameters: CompletionParameters,
            result: CompletionResultSet,
        ) {
            val project = file.project
            val anchor = file.virtualFile?.path
            val packages = NpmPackageResolver(project).discoverAllDependencyPackageNames(anchor)
            InteropCompletionItems.emitNpmPackages(packages, result)
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
