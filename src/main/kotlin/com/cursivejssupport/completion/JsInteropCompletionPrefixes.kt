package com.cursivejssupport.completion

import com.cursivejssupport.util.InteropDebugLog
import com.cursivejssupport.util.JsInteropPsi
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import kotlin.math.max
import kotlin.math.min

internal object JsInteropCompletionPrefixes {

    /**
     * Strips completion dummy text, then removes list/vector wrappers before the first `js/`
     * (e.g. `(js/document.cre` → `js/document.cre`) so completion and prefix matching see a stable `js/...` prefix.
     */
    fun normalizedEffectiveJsForCompletion(raw: String): String {
        var t = raw
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .trimStart()
        val idx = t.indexOf("js/")
        if (idx <= 0) return t
        val before = t.substring(0, idx)
        if (before.any { !it.isWhitespace() && it != '(' && it != '[' && it != '{' }) {
            return t
        }
        return t.substring(idx)
    }

    /**
     * ClojureScript uses dots for members (`js/document.createRange`). A `/` after the first global
     * (e.g. `document/cursive-completion` from dummy PSI) is not valid interop — drop from the first `/` onward.
     */
    fun collapseInvalidSlashesInJsInteropNormalizedPrefix(normalizedStartsWithJs: String): String {
        val n = normalizedStartsWithJs.trim()
        if (!n.startsWith("js/")) return n
        val rest = n.removePrefix("js/")
        val slash = rest.indexOf('/')
        if (slash < 0) return n
        return "js/" + rest.substring(0, slash)
    }

    /** True when [collapseInvalidSlashesInJsInteropNormalizedPrefix] would remove an invalid `/...` tail (typed `/` after `js/foo`). */
    fun shouldSuppressAutoPopupAfterInvalidJsSlash(documentSliceUpToCaret: String): Boolean {
        val n = normalizedEffectiveJsForCompletion(documentSliceUpToCaret)
        if (!n.startsWith("js/")) return false
        return n != collapseInvalidSlashesInJsInteropNormalizedPrefix(n)
    }

    /**
     * Single predicate for TypedHandler and [CompletionContributor.invokeAutoPopup]: suppress scheduling completion
     * when the caret is immediately after a `/` that continues an invalid `js/Global/...` dummy tail, using the
     * document slice and (when needed) the `js/` tail anchored at the last `js/` before the caret.
     */
    fun shouldSuppressInvalidJsSlashAutoPopup(document: Document, caretOffset: Int): Boolean {
        val len = document.textLength
        val off = min(caretOffset, len)
        if (off <= 0) return false
        return InteropCompletionIntentParser.shouldSuppressInvalidSlash(document.charsSequence, off, '/')
    }

    /** Text-only variant for tests and for callers that already have a slice ending at the caret. */
    fun shouldSuppressInvalidJsSlashFromEditorSlice(slice: String): Boolean {
        if (shouldSuppressAutoPopupAfterInvalidJsSlash(slice)) return true
        if (slice.isEmpty() || slice.last() != '/') return false
        val jsAt = slice.lastIndexOf("js/")
        if (jsAt < 0) return false
        val tail = slice.substring(jsAt)
        val n = normalizedEffectiveJsForCompletion(tail)
        if (!n.startsWith("js/")) return false
        return n != collapseInvalidSlashesInJsInteropNormalizedPrefix(n)
    }

    private fun normalizedAndCollapsedJsCandidate(raw: String): String {
        val n = normalizedEffectiveJsForCompletion(raw)
        return collapseInvalidSlashesInJsInteropNormalizedPrefix(n)
    }

    /**
     * Effective `js/...` user prefix for completion: prefers the document suffix from the last `js/`,
     * then PSI symbol / raw user text (handles `(js/document.cre` and similar).
     */
    fun effectiveJsInteropUserTextForJsBranch(
        parameters: CompletionParameters,
        position: PsiElement,
        userText: String,
        traceEffectiveJs: Boolean = true,
    ): String {
        val doc = parameters.editor.document
        val offset = min(parameters.offset, doc.textLength)
        val start = max(0, offset - 512)
        val slice = doc.getText(TextRange(start, offset))
        fun stripDummy(s: String) = s
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .trim()

        val docJs = slice.lastIndexOf("js/").takeIf { it >= 0 }?.let {
            normalizedAndCollapsedJsCandidate(stripDummy(slice.substring(it)))
        }
        val userJs = userText.lastIndexOf("js/").takeIf { it >= 0 }?.let {
            normalizedAndCollapsedJsCandidate(stripDummy(userText.substring(it)))
        }
        val symJs = JsInteropPsi.jsQualifiedSymbolText(position)
            ?.let { normalizedAndCollapsedJsCandidate(stripDummy(it)) }
        val chosen = listOfNotNull(docJs, userJs, symJs)
            .filter { it.startsWith("js/") }
            .maxByOrNull { it.length }
            ?: symJs
            ?: run {
                val u = stripDummy(userText)
                if (u.contains("js/")) normalizedAndCollapsedJsCandidate(u) else u
            }
        if (traceEffectiveJs) {
            InteropDebugLog.debug(
                "[interop-completion] effectiveJs: offset=$offset sliceLen=${slice.length} " +
                    "docJs=$docJs userJs=$userJs symJs=$symJs -> eff='$chosen'",
            )
        }
        return chosen
    }
}
