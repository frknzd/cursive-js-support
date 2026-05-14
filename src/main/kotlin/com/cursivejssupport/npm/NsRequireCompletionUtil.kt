package com.cursivejssupport.npm

import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.psi.PsiFile

/**
 * Detects caret inside a string literal that is the first element of an `ns` `:require` vector
 * (npm-style `"package-name"`), and returns the partial package name typed so far.
 */
object NsRequireCompletionUtil {

    fun npmPackagePrefixInRequire(file: PsiFile, caretOffset: Int): String? =
        npmPackagePrefixInRequireText(file.text, caretOffset)

    /** Visible for unit tests. */
    internal fun npmPackagePrefixInRequireText(t: String, caretOffset: Int): String? {
        if (caretOffset !in 1..t.length) return null

        var i = caretOffset - 1
        while (i >= 0 && (t[i].isWhitespace() || t[i] == '\u0000')) i--
        if (i < 0) return null

        // Find opening quote of current string (start from last non-ws before caret)
        var p = i
        var escaped = false
        while (p >= 0) {
            val c = t[p]
            if (escaped) {
                escaped = false
                p--
                continue
            }
            if (c == '\\') {
                escaped = true
                p--
                continue
            }
            if (c == '"') break
            if (c == '\n' || c == '\r') return null
            p--
        }
        if (p < 0) return null
        val quoteStart = p

        val head = t.substring(0, quoteStart)
        if (!head.contains(":require")) return null
        if (!head.contains("(ns")) return null

        val innerEnd = caretOffset.coerceAtMost(t.length)
        var inner = t.substring(quoteStart + 1, innerEnd)
        inner = inner.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
        if ('"' in inner) return null

        return inner.trim().ifEmpty { "" }
    }
}
