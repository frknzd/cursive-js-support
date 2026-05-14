package com.cursivejssupport.completion

import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import kotlin.math.max
import kotlin.math.min

internal enum class InteropCompletionKind {
    None,
    Js,
    DotMember,
    NpmAlias,
    General,
}

internal data class RecentInteropTypedChar(
    val char: Char,
    val offset: Int,
)

internal data class InteropCompletionIntent(
    val kind: InteropCompletionKind,
    val typedChar: Char?,
    val logicalPrefix: String,
    val receiverSegments: List<String> = emptyList(),
    val memberPrefix: String = "",
    val hadTerminalDot: Boolean = false,
    val hadInvalidSlash: Boolean = false,
    val namespace: String? = null,
    val exportName: String? = null,
    val npmMemberSegments: List<String> = emptyList(),
) {
    val jsPathRaw: String
        get() = when {
            receiverSegments.isEmpty() -> ""
            hadTerminalDot && memberPrefix.isEmpty() -> receiverSegments.joinToString(".") + "."
            memberPrefix.isNotEmpty() -> (receiverSegments + memberPrefix).joinToString(".")
            else -> receiverSegments.joinToString(".")
        }
}

internal object InteropCompletionIntentStore {
    private val KEY = Key.create<RecentInteropTypedChar>("cursive-js-support.recentInteropTypedChar")

    fun remember(editor: Editor, char: Char, offset: Int) {
        editor.putUserData(KEY, RecentInteropTypedChar(char, offset))
    }

    fun recentFor(editor: Editor, completionOffset: Int): RecentInteropTypedChar? {
        val recent = editor.getUserData(KEY) ?: return null
        return if (kotlin.math.abs(recent.offset - completionOffset) <= 2) recent else null
    }
}

internal object InteropCompletionIntentParser {
    private val dummyTokens = listOf(
        CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED,
        CompletionUtilCore.DUMMY_IDENTIFIER,
        "cursive-completion",
    )

    fun parse(
        documentText: CharSequence,
        caretOffset: Int,
        rawLogicalText: String,
        typedChar: Char?,
        aliases: Map<String, String> = emptyMap(),
    ): InteropCompletionIntent {
        val off = caretOffset.coerceIn(0, documentText.length)
        val sliceStart = max(0, off - 640)
        val slice = documentText.subSequence(sliceStart, off).toString()
        val logical = stripDummy(rawLogicalText).trim()
        val editorChar = if (off > 0) documentText[off - 1] else null
        val effectiveTypedChar = typedChar ?: editorChar

        parseJs(slice, logical, effectiveTypedChar)?.let { return it }
        parseDotMember(logical, effectiveTypedChar)?.let { return it }
        parseNpmAlias(logical, effectiveTypedChar, aliases)?.let { return it }

        return InteropCompletionIntent(
            kind = InteropCompletionKind.General,
            typedChar = effectiveTypedChar,
            logicalPrefix = logical,
        )
    }

    fun shouldSuppressInvalidSlash(documentText: CharSequence, caretOffset: Int, typedChar: Char? = '/'): Boolean {
        val off = caretOffset.coerceIn(0, documentText.length)
        val sliceStart = max(0, off - 640)
        val slice = documentText.subSequence(sliceStart, off).toString()
        return parseJs(slice, "", typedChar)?.hadInvalidSlash == true
    }

    private fun parseJs(slice: String, logical: String, typedChar: Char?): InteropCompletionIntent? {
        val candidate = jsCandidate(slice, logical, typedChar) ?: return null
        val withoutPrefix = candidate.removePrefix("js/")
        if (withoutPrefix.isEmpty()) {
            return InteropCompletionIntent(
                kind = InteropCompletionKind.Js,
                typedChar = typedChar,
                logicalPrefix = "js/",
            )
        }
        // CRITICAL: Cursive's dummy PSI can inject a fake `/` between the global and the
        // dummy identifier (so a logical-derived candidate looks like `js/document/.`).
        // The document slice is the source of truth for whether the user actually typed
        // an invalid slash. Validate slash-state against the slice alone.
        val sliceCand = sliceOnlyJsCandidate(slice, typedChar)
        val invalidSlash = when {
            sliceCand != null ->
                hasInvalidSlashAfterJsGlobal(sliceCand) ||
                    (typedChar == '/' && slashWouldFollowJsGlobal(slice, ""))
            else ->
                hasInvalidSlashAfterJsGlobal(candidate) ||
                    (typedChar == '/' && slashWouldFollowJsGlobal(slice, logical))
        }
        val collapsed = collapseInvalidSlash(candidate)
        val hadTerminalDot = typedChar == '.' || collapsed.endsWith(".")
        val path = collapsed.removePrefix("js/")
        val pathTrimmed = path.trimEnd('.')
        val parts = pathTrimmed.split('.').map { stripDummy(it).trim() }.filter { it.isNotEmpty() }
        val receiver = when {
            parts.isEmpty() -> emptyList()
            hadTerminalDot -> parts
            parts.size == 1 -> parts
            else -> parts.dropLast(1)
        }
        val memberPrefix = when {
            hadTerminalDot || parts.size <= 1 -> ""
            else -> parts.last()
        }
        return InteropCompletionIntent(
            kind = if (invalidSlash) InteropCompletionKind.None else InteropCompletionKind.Js,
            typedChar = typedChar,
            logicalPrefix = if (hadTerminalDot && !collapsed.endsWith(".")) "$collapsed." else collapsed,
            receiverSegments = receiver,
            memberPrefix = memberPrefix,
            hadTerminalDot = hadTerminalDot,
            hadInvalidSlash = invalidSlash,
        )
    }

    private fun parseDotMember(logical: String, typedChar: Char?): InteropCompletionIntent? {
        val l = stripDummy(logical)
        if (!l.startsWith(".") && typedChar != '-') return null
        val name = l.removePrefix(".-").removePrefix(".").trim()
        return InteropCompletionIntent(
            kind = InteropCompletionKind.DotMember,
            typedChar = typedChar,
            logicalPrefix = l,
            memberPrefix = name,
        )
    }

    private fun parseNpmAlias(logical: String, typedChar: Char?, aliases: Map<String, String>): InteropCompletionIntent? {
        val l = stripDummy(logical)
        val ns = l.substringBefore("/", missingDelimiterValue = "")
        if (ns.isBlank() || !aliases.containsKey(ns)) return null
        val rest = l.substringAfter("/", missingDelimiterValue = "")
        val export = rest.substringBefore('.').substringBefore('/').trim()
        val afterExport = rest.removePrefix(export).let { tail ->
            if (tail.startsWith('/')) "" else tail.trimStart('.')
        }
        val terminalDot = typedChar == '.' || rest.endsWith(".")
        val parts = afterExport.trimEnd('.')
            .split('.')
            .map { stripDummy(it).substringBefore('/').trim() }
            .filter { it.isNotEmpty() }
        val memberPrefix = if (terminalDot || parts.isEmpty()) "" else parts.last()
        val memberSegments = if (terminalDot) parts else parts.dropLast(1)
        return InteropCompletionIntent(
            kind = InteropCompletionKind.NpmAlias,
            typedChar = typedChar,
            logicalPrefix = l,
            hadTerminalDot = terminalDot,
            namespace = ns,
            exportName = export,
            npmMemberSegments = memberSegments,
            memberPrefix = memberPrefix,
        )
    }

    private fun jsCandidate(slice: String, logical: String, typedChar: Char?): String? {
        val fromSlice = slice.lastIndexOf("js/").takeIf { it >= 0 }?.let { slice.substring(it) }
        val fromLogical = logical.lastIndexOf("js/").takeIf { it >= 0 }?.let { logical.substring(it) }
        val raw = listOfNotNull(fromSlice, fromLogical)
            .map { normalizeCandidate(it, typedChar) }
            .filter { it.startsWith("js/") }
            .maxByOrNull { it.length }
        return raw
    }

    /** Slice-only `js/...` candidate (document truth, no Cursive PSI artifacts). */
    private fun sliceOnlyJsCandidate(slice: String, typedChar: Char?): String? {
        val idx = slice.lastIndexOf("js/").takeIf { it >= 0 } ?: return null
        return normalizeCandidate(slice.substring(idx), typedChar).takeIf { it.startsWith("js/") }
    }

    private fun normalizeCandidate(raw: String, typedChar: Char?): String {
        var t = stripDummy(raw).trimStart()
        val stop = t.indexOfFirst { it.isWhitespace() || it == ')' || it == ']' || it == '}' || it == '"' }
        if (stop >= 0) t = t.substring(0, stop)
        if (typedChar == '.' && t.startsWith("js/") && !t.endsWith(".") && !hasInvalidSlashAfterJsGlobal(t)) {
            t += "."
        }
        return t
    }

    private fun stripDummy(text: String): String {
        var t = text
        for (token in dummyTokens) {
            t = t.replace(token, "")
        }
        return t
    }

    private fun hasInvalidSlashAfterJsGlobal(candidate: String): Boolean {
        val rest = candidate.removePrefix("js/")
        return rest.indexOf('/') >= 0 && rest.substringBefore('/').isNotBlank()
    }

    private fun slashWouldFollowJsGlobal(slice: String, logical: String): Boolean {
        val c = jsCandidate(slice, logical, null) ?: return false
        val rest = c.removePrefix("js/").trimEnd('/')
        return rest.isNotBlank() && !rest.contains('.')
    }

    private fun collapseInvalidSlash(candidate: String): String {
        val rest = candidate.removePrefix("js/")
        val slash = rest.indexOf('/')
        return if (slash >= 0) "js/" + rest.substring(0, slash) else candidate
    }

    fun documentSlice(documentText: CharSequence, caretOffset: Int, radius: Int = 640): String {
        val off = min(caretOffset, documentText.length)
        return documentText.subSequence(max(0, off - radius), off).toString()
    }
}
