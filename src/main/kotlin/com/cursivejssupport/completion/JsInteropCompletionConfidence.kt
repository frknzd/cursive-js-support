package com.cursivejssupport.completion

import com.cursivejssupport.util.JsInteropPsi
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState
import kotlin.math.max
import kotlin.math.min

/**
 * Forces IntelliJ to open the completion auto-popup inside `js/...` interop contexts.
 *
 * The platform's default behavior (and Cursive's own confidences) often vote to skip
 * auto-popup for characters like `.`, leaving us with `TypedHandlerDelegate.checkAutoPopup`
 * scheduling popups that never materialize. By returning [ThreeState.NO] here we tell
 * the platform "definitely do not skip", which causes [com.intellij.codeInsight.AutoPopupController]
 * to actually invoke `CodeCompletionHandlerBase`.
 *
 * We are intentionally narrow: only fire `NO` when we can prove we're inside a js interop
 * form (text near caret contains `js/` or the enclosing editor symbol is a js form / dot
 * member). Otherwise we return UNSURE so other contributors keep their behavior.
 */
class JsInteropCompletionConfidence : CompletionConfidence() {

    override fun shouldSkipAutopopup(
        contextElement: PsiElement,
        psiFile: PsiFile,
        offset: Int,
    ): ThreeState {
        if (!isClojureDialectFile(psiFile)) return ThreeState.UNSURE

        val doc = psiFile.viewProvider.document ?: return ThreeState.UNSURE
        val end = min(offset, doc.textLength).coerceAtLeast(0)
        if (end == 0) return ThreeState.UNSURE
        val start = max(0, end - SLICE_LOOKBACK)
        val slice = doc.getText(TextRange(start, end))

        if (Companion.sliceLooksJsInterop(slice)) return ThreeState.NO

        val sym = JsInteropPsi.enclosingEditorSymbol(contextElement)
        if (sym != null) {
            if (sym.namespace == "js") return ThreeState.NO
            val full = sym.text?.trim().orEmpty()
            if (full.startsWith("js/")) return ThreeState.NO
            if (full.startsWith(".")) return ThreeState.NO
        }

        JsInteropPsi.jsQualifiedSymbolText(contextElement)?.let {
            if (it.startsWith("js/")) return ThreeState.NO
        }

        return ThreeState.UNSURE
    }

    private fun isClojureDialectFile(file: PsiFile): Boolean {
        val n = file.name
        return n.endsWith(".cljs", ignoreCase = true) ||
            n.endsWith(".cljc", ignoreCase = true) ||
            n.endsWith(".clj", ignoreCase = true)
    }

    companion object {
        internal const val SLICE_LOOKBACK = 256

        /**
         * Pure helper: does the supplied document slice (which should end at the caret)
         * look like the user is inside / right after a `js/...` interop form?
         */
        internal fun sliceLooksJsInterop(slice: CharSequence): Boolean {
            if (slice.isEmpty()) return false
            if (slice.startsWith("js/")) return true
            if (containsToken(slice, "(js/")) return true
            if (containsToken(slice, " js/")) return true
            if (containsToken(slice, "\njs/")) return true
            if (containsToken(slice, "\tjs/")) return true
            return false
        }

        private fun containsToken(slice: CharSequence, needle: String): Boolean {
            if (needle.length > slice.length) return false
            var i = 0
            val limit = slice.length - needle.length
            while (i <= limit) {
                var match = true
                for (j in needle.indices) {
                    if (slice[i + j] != needle[j]) { match = false; break }
                }
                if (match) return true
                i++
            }
            return false
        }
    }
}
