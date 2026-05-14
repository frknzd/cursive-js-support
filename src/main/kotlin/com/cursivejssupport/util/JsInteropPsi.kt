package com.cursivejssupport.util

import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import cursive.psi.impl.symbols.ClEditorSymbol

/**
 * Resolves full Clojure/ClojureScript editor symbol text (avoids leaf-only PSI for dotted `js/` and npm forms).
 */
object JsInteropPsi {

    fun enclosingEditorSymbol(element: PsiElement): ClEditorSymbol? =
        PsiTreeUtil.getParentOfType(element, ClEditorSymbol::class.java, false)

    fun expandTargetElement(sourceElement: PsiElement): PsiElement {
        var targetElement: PsiElement = sourceElement
        while (targetElement.parent != null) {
            val parentText = targetElement.parent.text
            if (parentText.startsWith("(") || parentText.startsWith("[") ||
                parentText.startsWith("{") || parentText.startsWith("\"") ||
                parentText.any { it.isWhitespace() }) {
                break
            }
            targetElement = targetElement.parent
        }
        return targetElement
    }

    /** Prefer full `ClEditorSymbol` text for goto / declaration targets. */
    fun gotoSymbolText(sourceElement: PsiElement): String {
        enclosingEditorSymbol(sourceElement)?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return expandTargetElement(sourceElement).text.trim()
    }

    /** Full symbol text for completion (strips dummy identifier). */
    fun completionLogicalText(position: PsiElement, fallbackLeafText: String): String {
        val sym = enclosingEditorSymbol(position)
        val raw = sym?.text ?: fallbackLeafText
        return raw
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .trim()
    }
}
