package com.cursivejssupport.npm

import com.intellij.codeInsight.completion.CompletionUtilCore

/**
 * Document-driven parser for the caret slot inside a `(ns ... (:require [...]))` spec.
 *
 * Identifies whether the caret sits in:
 * - a package string literal `["<partial>"]`,
 * - the keyword slot right after `["pkg"` (waiting for `:as` / `:refer` / `:rename` / `:default`),
 * - a `:refer [<partial>]` (or `:refer (<partial>)`) collection,
 * - a `:rename {<partial>` mapping.
 *
 * Returns `null` outside any `(ns ... (:require …))` form. Pure over [doc] / [caret] — no PSI.
 *
 * Internally a single forward lex over a bounded window builds a stack of open delimiters and
 * tracks whether the caret is inside a string / comment. All slot decisions are then made over
 * stable positional information rather than fragile backward scans.
 */
object InteropNsRequireParser {

    sealed interface Slot {
        val prefix: String
        val replacementStart: Int

        /** Inside the package string literal `["<partial>"]`. */
        data class Package(
            override val prefix: String,
            override val replacementStart: Int,
        ) : Slot

        /** Right after `["pkg"` waiting for an option keyword (`:as`, `:refer`, …). */
        data class Keyword(
            val packageName: String,
            val availableKeywords: List<String>,
            override val prefix: String,
            override val replacementStart: Int,
        ) : Slot

        /**
         * Inside `:refer [<partial>]` or `:rename {<partial>`. Only the package's exports are
         * valid here; option keywords belong in the [Keyword] slot.
         */
        data class Refer(
            val packageName: String,
            override val prefix: String,
            override val replacementStart: Int,
        ) : Slot
    }

    private const val MAX_SCAN = 16384
    private const val ALL_KEYWORDS = ":as :refer :rename :default :all"

    fun parse(doc: CharSequence, caret: Int): Slot? {
        if (caret !in 0..doc.length) return null
        val scanStart = (caret - MAX_SCAN).coerceAtLeast(0)
        val text = doc.subSequence(scanStart, caret).toString()
        val viewCaret = text.length
        val state = lex(text, viewCaret)
        if (state.insideLineComment) return null

        if (state.insideString && state.stringStart >= 0) {
            val openVec = state.openStack.lastOrNull { it.char == '[' } ?: return null
            // Inside a string literal that sits inside a `[ ... ]`; ensure the enclosing `(:require)`
            // form is on the stack.
            if (!state.hasRequireParent()) return null
            val opening = state.stringStart
            // Confirm the string starts inside the same `[` (require spec).
            if (opening < openVec.offset) return null
            val inner = text.substring(opening + 1, viewCaret).stripCompletionDummy()
            return Slot.Package(
                prefix = inner,
                replacementStart = opening + 1 + scanStart,
            )
        }

        // Identifier-style partial right of the last non-token char.
        val partialStart = identifierStart(text, viewCaret)
        val partial = text.substring(partialStart, viewCaret).stripCompletionDummy()

        // We need to be inside a collection that lives in a `(:require ...)` form.
        if (!state.hasRequireParent()) return null
        val innermost = state.openStack.lastOrNull() ?: return null

        // Inside a refer vector / rename map?
        if (innermost.char == '[' || innermost.char == '{' || innermost.char == '(') {
            val parent = state.openStack.dropLast(1).lastOrNull()
            if (parent != null && parent.char == '[') {
                val keyword = state.tokenImmediatelyBefore(innermost.offset, parent.offset)
                if (keyword == ":refer" || keyword == ":rename") {
                    val pkg = packageNameInRequireSpec(text, parent.offset) ?: return null
                    return Slot.Refer(
                        packageName = pkg,
                        prefix = partial,
                        replacementStart = partialStart + scanStart,
                    )
                }
            }
        }

        // Keyword slot inside the require spec: `["pkg" |` or `["pkg" :as Alias |`.
        if (innermost.char == '[') {
            val pkg = packageNameInRequireSpec(text, innermost.offset) ?: return null
            // Token immediately before our partial — must NOT be a `:` keyword that takes an argument.
            val prevTok = state.previousMeaningfulToken(partialStart, innermost.offset)
            val previousIsKeywordTakingArg = prevTok == ":as" || prevTok == ":default" ||
                prevTok == ":refer" || prevTok == ":rename" || prevTok == ":all"
            if (previousIsKeywordTakingArg) return null
            val unused = availableSpecKeywords(text, innermost.offset)
            return Slot.Keyword(
                packageName = pkg,
                availableKeywords = unused,
                prefix = partial,
                replacementStart = partialStart + scanStart,
            )
        }

        return null
    }

    // ─── Lex ────────────────────────────────────────────────────────────────

    private data class Open(val offset: Int, val char: Char)

    private data class LexState(
        val text: String,
        val insideString: Boolean,
        val stringStart: Int,
        val insideLineComment: Boolean,
        val openStack: List<Open>,
    ) {
        fun hasRequireParent(): Boolean {
            for (open in openStack) {
                if (open.char != '(') continue
                val after = open.offset + 1
                var k = after
                while (k < text.length && text[k].isWhitespace()) k++
                if (k + 8 <= text.length && text.regionMatches(k, ":require", 0, 8)) return true
            }
            return false
        }

        /**
         * Returns the token that ends right before [endOffset] within `(min, endOffset)` bounds,
         * or `null` when no token is found.
         */
        fun tokenImmediatelyBefore(endOffset: Int, minOffset: Int): String? {
            var i = endOffset - 1
            while (i > minOffset && text[i].isWhitespace()) i--
            if (i <= minOffset) return null
            val end = i + 1
            while (i > minOffset && !text[i].isWhitespace() && text[i] != '[' && text[i] != '{' && text[i] != '(') i--
            val start = if (text[i].isWhitespace() || text[i] == '[' || text[i] == '{' || text[i] == '(') i + 1 else i
            if (start >= end) return null
            return text.substring(start, end)
        }

        fun previousMeaningfulToken(endOffset: Int, minOffset: Int): String? {
            var i = endOffset - 1
            while (i > minOffset && text[i].isWhitespace()) i--
            if (i <= minOffset) return null
            val end = i + 1
            while (i > minOffset && !text[i].isWhitespace() && text[i] != '[' && text[i] != '{' && text[i] != '(') i--
            val start = if (text[i].isWhitespace() || text[i] == '[' || text[i] == '{' || text[i] == '(') i + 1 else i
            if (start >= end) return null
            return text.substring(start, end)
        }
    }

    private fun lex(text: String, until: Int): LexState {
        val stack = ArrayDeque<Open>()
        var inString = false
        var stringStart = -1
        var inLineComment = false
        var i = 0
        while (i < until) {
            val c = text[i]
            if (inLineComment) {
                if (c == '\n' || c == '\r') inLineComment = false
                i++
                continue
            }
            if (inString) {
                if (c == '\\' && i + 1 < until) { i += 2; continue }
                if (c == '"' || c == '\n' || c == '\r') {
                    inString = false
                    stringStart = -1
                }
                i++
                continue
            }
            when (c) {
                ';' -> { inLineComment = true; i++; continue }
                '"' -> { inString = true; stringStart = i; i++; continue }
                '(' -> stack.addLast(Open(i, '('))
                '[' -> stack.addLast(Open(i, '['))
                '{' -> stack.addLast(Open(i, '{'))
                ')' -> { while (stack.isNotEmpty() && stack.last().char != '(') stack.removeLast(); if (stack.isNotEmpty()) stack.removeLast() }
                ']' -> { while (stack.isNotEmpty() && stack.last().char != '[') stack.removeLast(); if (stack.isNotEmpty()) stack.removeLast() }
                '}' -> { while (stack.isNotEmpty() && stack.last().char != '{') stack.removeLast(); if (stack.isNotEmpty()) stack.removeLast() }
            }
            i++
        }
        return LexState(
            text = text,
            insideString = inString,
            stringStart = stringStart,
            insideLineComment = inLineComment,
            openStack = stack.toList(),
        )
    }

    private fun packageNameInRequireSpec(text: String, openIndex: Int): String? {
        var i = openIndex + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '"') return null
        val nameStart = i + 1
        var j = nameStart
        while (j < text.length && text[j] != '"' && text[j] != '\n') j++
        if (j >= text.length || text[j] != '"') return null
        val name = text.substring(nameStart, j).stripCompletionDummy().trim()
        return name.takeIf { it.isNotEmpty() }
    }

    private fun availableSpecKeywords(text: String, requireSpecOpen: Int): List<String> {
        val end = matchingCloseOrTextEnd(text, requireSpecOpen)
        val body = text.substring(requireSpecOpen + 1, end)
        return ALL_KEYWORDS.split(' ').filter { !containsToken(body, it) }
    }

    private fun matchingCloseOrTextEnd(text: String, openIndex: Int): Int {
        val open = text[openIndex]
        val close = when (open) {
            '[' -> ']'
            '{' -> '}'
            '(' -> ')'
            else -> return text.length
        }
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            val c = text[i]
            if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return text.length
    }

    private fun containsToken(text: String, token: String): Boolean {
        var i = 0
        while (i + token.length <= text.length) {
            if (text.regionMatches(i, token, 0, token.length)) {
                val before = if (i == 0) ' ' else text[i - 1]
                val after = if (i + token.length == text.length) ' ' else text[i + token.length]
                if (!isSymbolChar(before) && !isSymbolChar(after)) return true
            }
            i++
        }
        return false
    }

    private fun identifierStart(text: String, caret: Int): Int {
        var i = caret
        while (i > 0 && isSymbolChar(text[i - 1])) i--
        return i
    }

    private fun isSymbolChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_' || c == '$' || c == '.' ||
            c == '?' || c == '!' || c == '*' || c == '+' || c == '=' || c == ':'

    private fun String.stripCompletionDummy(): String =
        this.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .replace("cursive-completion", "")
}
