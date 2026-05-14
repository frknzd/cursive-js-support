package com.cursivejssupport.completion

import com.cursivejssupport.npm.InteropNsRequireParser
import com.intellij.codeInsight.completion.CompletionUtilCore

/**
 * Document-driven detector for the interop completion context.
 *
 * Inputs are pure: a [doc] character sequence, a [caret] offset, and the project's npm
 * `(:require)` [aliases] (`alias name → npm package name`). The detector NEVER reads PSI —
 * Cursive's PSI for partially-typed symbols is unstable under dummy-identifier injection, so
 * the document is the only reliable source.
 *
 * The contributor and the typed-handler both go through here so popup-trigger and result
 * production agree on what the user is doing.
 */
object InteropContextDetector {

    private const val MAX_SCAN = 1024

    fun detect(
        doc: CharSequence,
        caret: Int,
        aliases: Map<String, String> = emptyMap(),
    ): InteropCompletionContext {
        if (caret < 0 || caret > doc.length) return InteropCompletionContext.None
        if (caret == 0) return InteropCompletionContext.None

        // 1. ns/:require/:refer slots win over generic identifier classification because
        // string contents and symbol contents in those forms have very different meanings.
        InteropNsRequireParser.parse(doc, caret)?.let { return fromRequireSlot(it) }

        // 2. Otherwise look at the identifier-like token immediately to the left of the caret.
        val tokenStart = scanTokenStart(doc, caret)
        if (tokenStart == caret) return InteropCompletionContext.None
        val token = doc.subSequence(tokenStart, caret).toString().stripCompletionDummy()
        if (token.isEmpty()) return InteropCompletionContext.None

        return classifyToken(token, tokenStart, aliases)
    }

    private fun fromRequireSlot(slot: InteropNsRequireParser.Slot): InteropCompletionContext =
        when (slot) {
            is InteropNsRequireParser.Slot.Package ->
                InteropCompletionContext.NsRequirePackage(
                    prefix = slot.prefix,
                    replacementStart = slot.replacementStart,
                )
            is InteropNsRequireParser.Slot.Keyword ->
                InteropCompletionContext.NsRequireKeyword(
                    packageName = slot.packageName,
                    availableKeywords = slot.availableKeywords,
                    prefix = slot.prefix,
                    replacementStart = slot.replacementStart,
                )
            is InteropNsRequireParser.Slot.Refer ->
                InteropCompletionContext.NsRefer(
                    packageName = slot.packageName,
                    prefix = slot.prefix,
                    replacementStart = slot.replacementStart,
                )
        }

    private fun classifyToken(
        token: String,
        tokenStart: Int,
        aliases: Map<String, String>,
    ): InteropCompletionContext {
        // (.-prop  → DotMember(asProperty = true)
        if (token.startsWith(".-")) {
            val member = token.substring(2)
            val memberStart = tokenStart + 2
            return InteropCompletionContext.DotMember(
                asProperty = true,
                prefix = member,
                replacementStart = memberStart,
            )
        }
        // (.method → DotMember(asProperty = false)
        if (token.startsWith(".")) {
            val member = token.substring(1)
            // Reject `.` followed by another `.` (e.g. `..foo`) — not a member form.
            if (member.startsWith(".")) return InteropCompletionContext.None
            val memberStart = tokenStart + 1
            return InteropCompletionContext.DotMember(
                asProperty = false,
                prefix = member,
                replacementStart = memberStart,
            )
        }

        // js/...  (handles both `js/global` and `js/document.member`)
        if (token.startsWith("js/")) {
            return classifyJsToken(token, tokenStart)
        }

        // Alias/... (npm alias)
        val slashIdx = token.indexOf('/')
        if (slashIdx > 0) {
            val ns = token.substring(0, slashIdx)
            val pkg = aliases[ns]
            if (pkg != null) {
                return classifyNpmAliasToken(ns, pkg, token, slashIdx, tokenStart)
            }
        }

        return InteropCompletionContext.None
    }

    private fun classifyJsToken(token: String, tokenStart: Int): InteropCompletionContext {
        val afterJs = token.substring(3) // strip "js/"
        val afterJsStart = tokenStart + 3
        if ('.' !in afterJs) {
            return InteropCompletionContext.JsGlobalName(
                prefix = afterJs,
                replacementStart = afterJsStart,
            )
        }
        val parts = afterJs.split('.')
        // After a trailing `.` we have an empty member prefix; otherwise the last token is the partial member.
        val receiver = parts.dropLast(1).filter { it.isNotEmpty() }
        val memberPrefix = parts.last()
        val memberStart = tokenStart + token.length - memberPrefix.length
        if (receiver.isEmpty()) {
            // Token was something like `js/.foo` — degenerate, fall back to globals on the partial.
            return InteropCompletionContext.JsGlobalName(prefix = memberPrefix, replacementStart = memberStart)
        }
        return InteropCompletionContext.JsChainMember(
            receiverSegments = receiver,
            prefix = memberPrefix,
            replacementStart = memberStart,
        )
    }

    private fun classifyNpmAliasToken(
        alias: String,
        packageName: String,
        token: String,
        slashIdx: Int,
        tokenStart: Int,
    ): InteropCompletionContext {
        val rest = token.substring(slashIdx + 1)
        val restStart = tokenStart + slashIdx + 1
        if ('.' !in rest) {
            return InteropCompletionContext.NpmAliasExport(
                alias = alias,
                packageName = packageName,
                prefix = rest,
                replacementStart = restStart,
            )
        }
        val parts = rest.split('.')
        val exportName = parts.first()
        if (exportName.isEmpty()) {
            return InteropCompletionContext.NpmAliasExport(
                alias = alias,
                packageName = packageName,
                prefix = "",
                replacementStart = restStart,
            )
        }
        val tail = parts.drop(1)
        val receiver = tail.dropLast(1).filter { it.isNotEmpty() }
        val memberPrefix = tail.last()
        val memberStart = tokenStart + token.length - memberPrefix.length
        return InteropCompletionContext.NpmAliasExportMember(
            alias = alias,
            packageName = packageName,
            exportName = exportName,
            receiverSegments = receiver,
            prefix = memberPrefix,
            replacementStart = memberStart,
        )
    }

    /**
     * Walks backward over interop token characters. Stops on whitespace, brackets, quotes,
     * `;` (line comment start), `,`, `^`, `@`, `~`, ``\``.
     */
    private fun scanTokenStart(doc: CharSequence, caret: Int): Int {
        var i = caret
        val limit = (caret - MAX_SCAN).coerceAtLeast(0)
        while (i > limit && isTokenChar(doc[i - 1])) i--
        return i
    }

    private fun isTokenChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '-' || c == '_' || c == '$' || c == '.' || c == '/' || c == '?' || c == '!' || c == '*' || c == '+' || c == '='

    private fun String.stripCompletionDummy(): String =
        this.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .replace("cursive-completion", "")
}
