package com.cursivejssupport.completion

import com.cursivejssupport.npm.InteropNsRequireParser
import com.cursivejssupport.npm.NpmBinding
import com.cursivejssupport.npm.NpmBindingKind
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
        aliases: Map<String, NpmBinding> = emptyMap(),
        knownGoogNamespaces: Set<String> = emptySet(),
    ): InteropCompletionContext {
        if (caret < 0 || caret > doc.length) return InteropCompletionContext.None
        if (caret == 0) return InteropCompletionContext.None

        // 1. ns/:require/:refer slots win over generic identifier classification because
        // string contents and symbol contents in those forms have very different meanings.
        InteropNsRequireParser.parse(doc, caret)?.let { return fromRequireSlot(it) }

        // 2. Otherwise look at the identifier-like token immediately to the left of the caret.
        val tokenStart = scanTokenStart(doc, caret)
        if (tokenStart == caret) {
            detectDotDotForm(doc, tokenStart, caret)?.let { return it }
            return if (aliases.isNotEmpty()) {
                InteropCompletionContext.NpmAliasName(aliases, "", caret)
            } else {
                InteropCompletionContext.None
            }
        }
        val token = doc.subSequence(tokenStart, caret).toString().stripCompletionDummy()
        if (token.isEmpty()) {
            detectDotDotForm(doc, tokenStart, caret)?.let { return it }
            return if (aliases.isNotEmpty()) {
                InteropCompletionContext.NpmAliasName(aliases, "", caret)
            } else {
                InteropCompletionContext.None
            }
        }

        detectDotDotForm(doc, tokenStart, caret)?.let { return it }
        return classifyToken(token, tokenStart, aliases, knownGoogNamespaces)
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
            is InteropNsRequireParser.Slot.GoogNamespace ->
                InteropCompletionContext.GoogNamespaceRequire(
                    prefix = slot.prefix,
                    replacementStart = slot.replacementStart,
                )
        }

    private fun classifyToken(
        token: String,
        tokenStart: Int,
        aliases: Map<String, NpmBinding>,
        knownGoogNamespaces: Set<String> = emptySet(),
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

        val slashIdx = token.indexOf('/')
        if (slashIdx > 0) {
            val ns = token.substring(0, slashIdx)
            val binding = aliases[ns]
            return when {
                binding != null -> classifyNpmAliasToken(ns, binding, token, slashIdx, tokenStart)
                ns in knownGoogNamespaces -> classifyNpmAliasToken(ns, NpmBinding(ns, NpmBindingKind.AS), token, slashIdx, tokenStart)
                else -> classifyJsGlobalOrNamespace(ns, token, slashIdx, tokenStart)
            }
        }

        // goog namespace name — no slash yet, user is typing the namespace to use in code.
        // Must come before the dotIdx check so "goog.string" becomes GoogNamespaceName rather
        // than JsChainMember (which would fail: "goog" is not a js global with a known type).
        // Also match short prefixes like "go", "goo" so completions fire before the user types
        // the full word — but require ≥2 chars to avoid false positives on single-letter tokens.
        if (knownGoogNamespaces.isNotEmpty() && token.length >= 2 &&
            (token.startsWith("goog.") || "goog".startsWith(token))) {
            return InteropCompletionContext.GoogNamespaceName(
                prefix = token,
                replacementStart = tokenStart,
            )
        }

        val dotIdx = token.indexOf('.')
        if (dotIdx > 0) {
            val ns = token.substring(0, dotIdx)
            val binding = aliases[ns]
            return if (binding != null) {
                classifyNpmAliasToken(ns, binding, token, dotIdx, tokenStart)
            } else {
                classifyJsGlobalOrNamespace(ns, token, dotIdx, tokenStart)
            }
        }

        if (aliases.isNotEmpty()) {
            return InteropCompletionContext.NpmAliasName(aliases, token, tokenStart)
        }

        return InteropCompletionContext.None
    }

    private fun classifyJsGlobalOrNamespace(
        ns: String,
        token: String,
        separatorIdx: Int,
        tokenStart: Int,
    ): InteropCompletionContext {
        val rest = token.substring(separatorIdx + 1)
        val parts = rest.split('.')
        val receiver = listOf(ns) + parts.dropLast(1).filter { it.isNotEmpty() }
        val memberPrefix = parts.last()
        val memberStart = tokenStart + token.length - memberPrefix.length
        return InteropCompletionContext.JsChainMember(
            receiverSegments = receiver,
            prefix = memberPrefix,
            replacementStart = memberStart,
        )
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
        binding: NpmBinding,
        token: String,
        slashIdx: Int,
        tokenStart: Int,
    ): InteropCompletionContext {
        // VALUE bindings (DEFAULT, REFER) cannot be namespace-qualified: Alias/Foo is invalid
        // ClojureScript for a direct-value binding. Return None to avoid wrong completions.
        if (binding.kind == NpmBindingKind.DEFAULT || binding.kind == NpmBindingKind.REFER) {
            return InteropCompletionContext.None
        }
        val packageName = binding.packageName
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

    /**
     * Detects whether [tokenStart] is a chain-step position inside a `(.. root ...)` form.
     *
     * Scans backward from [tokenStart] collecting simple tokens and skipping nested forms.
     * Returns a [InteropCompletionContext.DotDotForm] when:
     * - the first token found is exactly `..` (the form head), AND
     * - at least one prior element (the root) has already been typed.
     *
     * Returns `null` when the user is at the root position (nothing before the `..` found) or
     * when the enclosing form's head is not `..`.
     */
    private fun detectDotDotForm(
        doc: CharSequence,
        tokenStart: Int,
        caret: Int,
    ): InteropCompletionContext.DotDotForm? {
        val limit = (tokenStart - MAX_SCAN).coerceAtLeast(0)
        var i = tokenStart
        val tokens = ArrayDeque<String>()

        outer@ while (i > limit) {
            // Skip whitespace
            while (i > limit && doc[i - 1].isWhitespace()) i--
            if (i <= limit) break

            when {
                doc[i - 1] == '(' -> break  // found enclosing open-paren
                doc[i - 1] == ')' || doc[i - 1] == ']' || doc[i - 1] == '}' -> {
                    val matchOpen = skipFormBackward(doc, i - 1, limit)
                    if (matchOpen < limit) break@outer
                    tokens.addFirst(doc.subSequence(matchOpen, i).toString())
                    i = matchOpen
                }
                isTokenChar(doc[i - 1]) -> {
                    val end = i
                    while (i > limit && isTokenChar(doc[i - 1])) i--
                    tokens.addFirst(doc.subSequence(i, end).toString())
                }
                else -> break  // unrecognised char (`;`, `"`, `@`, `#`, …): bail out
            }
        }

        if (tokens.isEmpty() || tokens.first() != "..") return null
        val chain = tokens.drop(1)
        if (chain.isEmpty()) return null  // user is at root position; existing logic handles it

        val prefix = doc.subSequence(tokenStart, caret).toString().stripCompletionDummy()
        return InteropCompletionContext.DotDotForm(
            priorChain = chain,
            prefix = prefix,
            replacementStart = tokenStart,
        )
    }

    /** Finds the matching open-delimiter for a close-delimiter at [closePos], scanning backward. */
    private fun skipFormBackward(doc: CharSequence, closePos: Int, limit: Int): Int {
        val closeChar = doc[closePos]
        val openChar = when (closeChar) { ')' -> '('; ']' -> '['; '}' -> '{'; else -> return closePos }
        var depth = 1
        var i = closePos - 1
        while (i >= limit && depth > 0) {
            when (doc[i]) { closeChar -> depth++; openChar -> { depth--; if (depth == 0) return i } }
            i--
        }
        return limit - 1
    }

    private fun String.stripCompletionDummy(): String =
        this.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
            .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
            .replace("cursive-completion", "")
}
