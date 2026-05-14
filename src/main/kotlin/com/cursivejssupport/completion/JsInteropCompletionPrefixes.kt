package com.cursivejssupport.completion

import com.cursivejssupport.util.JsInteropPsi
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import kotlin.math.max
import kotlin.math.min

internal object JsInteropCompletionPrefixes {

    /**
     * Effective `js/...` user prefix for completion: prefers the document suffix from the last `js/`,
     * then PSI symbol / raw user text (handles `(js/document.cre` and similar).
     */
    fun effectiveJsInteropUserTextForJsBranch(
        parameters: CompletionParameters,
        position: PsiElement,
        userText: String,
    ): String {
        val doc = parameters.editor.document
        val offset = min(parameters.offset, doc.textLength)
        val start = max(0, offset - 512)
        val slice = doc.getText(TextRange(start, offset))
        fun stripDummy(s: String) = s
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .trim()

        val docJs = slice.lastIndexOf("js/").takeIf { it >= 0 }?.let { stripDummy(slice.substring(it)) }
        val userJs = userText.lastIndexOf("js/").takeIf { it >= 0 }?.let { stripDummy(userText.substring(it)) }
        val sym = JsInteropPsi.enclosingEditorSymbol(position)
        val symJs = if (sym?.namespace == "js" && !sym.name.isNullOrBlank()) {
            stripDummy("js/${sym.name}")
        } else {
            null
        }
        return listOfNotNull(docJs, userJs, symJs)
            .filter { it.startsWith("js/") }
            .maxByOrNull { it.length }
            ?: symJs
            ?: stripDummy(userText)
    }
}
