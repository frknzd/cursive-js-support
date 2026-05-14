package com.cursivejssupport.util

import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import cursive.psi.impl.symbols.ClEditorSymbol

/**
 * Resolves full Clojure/ClojureScript editor symbol text (avoids leaf-only PSI for dotted `js/` and npm forms).
 */
object JsInteropPsi {

    fun enclosingEditorSymbol(element: PsiElement): ClEditorSymbol? =
        PsiTreeUtil.getParentOfType(element, ClEditorSymbol::class.java, false)

    /**
     * Cursive often represents `js/document.createRange` as one symbol node with children:
     * `js`, an `ns separator` `/`, and a separate symbol token `document.createRange`.
     *
     * Completion can be invoked on the leaf token (or a dummy replacement leaf), where
     * [enclosingEditorSymbol] may be unavailable or only expose the leaf. Reconstruct the
     * full `js/...` text from the sibling/parent PSI shape before falling back to raw leaf text.
     */
    fun jsQualifiedSymbolText(element: PsiElement, fallbackLeafText: String? = null): String? {
        val sym = enclosingEditorSymbol(element)
        if (sym?.namespace == "js" && !sym.name.isNullOrBlank()) {
            return stripCompletionDummy("js/${sym.name}")
        }
        sym?.text?.trim()?.takeIf { it.startsWith("js/") }?.let {
            return stripCompletionDummy(it)
        }

        reconstructJsQualifiedFromParents(element)?.let { return it }
        reconstructJsQualifiedFromSiblings(element, fallbackLeafText)?.let { return it }
        return null
    }

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
        jsQualifiedSymbolText(sourceElement)?.let { return it }
        enclosingEditorSymbol(sourceElement)?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return expandTargetElement(sourceElement).text.trim()
    }

    /** Full symbol text for completion (strips dummy identifier). */
    fun completionLogicalText(position: PsiElement, fallbackLeafText: String): String {
        val raw = jsQualifiedSymbolText(position, fallbackLeafText)
            ?: enclosingEditorSymbol(position)?.text
            ?: fallbackLeafText
        return raw
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .replace("cursive-completion", "")
            .trim()
    }

    private fun reconstructJsQualifiedFromParents(element: PsiElement): String? {
        var current: PsiElement? = element
        repeat(4) {
            val node = current ?: return@repeat
            node.text?.trim()?.takeIf { it.startsWith("js/") }?.let {
                return stripCompletionDummy(it)
            }
            val meaningful = node.children
                .filter { it !is PsiWhiteSpace && it.text.isNotBlank() }
            if (meaningful.size >= 3 &&
                meaningful[0].text == "js" &&
                meaningful[1].text == "/") {
                val name = meaningful[2].text?.trim().orEmpty()
                if (name.isNotBlank()) return stripCompletionDummy("js/$name")
            }
            current = node.parent
        }
        return null
    }

    private fun reconstructJsQualifiedFromSiblings(element: PsiElement, fallbackLeafText: String?): String? {
        val nameText = stripCompletionDummy((fallbackLeafText ?: element.text ?: "").trim())
        if (nameText.isBlank()) return null

        var prev = element.prevSibling
        while (prev is PsiWhiteSpace) prev = prev.prevSibling
        if (prev?.text != "/") return null
        var ns = prev.prevSibling
        while (ns is PsiWhiteSpace) ns = ns.prevSibling
        if (ns?.text != "js") return null

        return stripCompletionDummy("js/$nameText")
    }

    private fun stripCompletionDummy(text: String): String =
        text.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .replace("cursive-completion", "")
            .trim()
}
