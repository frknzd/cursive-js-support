package com.cursivejssupport.documentation

/**
 * Converts the lightly-marked-up JSDoc / MDN text shipped with TypeScript declarations into the
 * HTML subset that IntelliJ's documentation popup renders.
 *
 * The pipeline (intentionally small):
 *
 *  1. Trim and strip fenced code blocks (`@example` snippets — they look bad in popups).
 *  2. HTML-escape `< > &` so accidental angle brackets in JSDoc don't blow up rendering.
 *  3. Apply markdown→HTML substitutions on the escaped text:
 *     - `[text](url)` → `<a href="url">text</a>` (MDN, MSDN, anything HTTP/S).
 *     - `` `code` `` → `<code>code</code>`.
 *     - `**bold**` → `<b>bold</b>`, `*em*` / `_em_` → `<i>em</i>`.
 *  4. Collapse duplicate `MDN Reference` lines (the dts extractor often emits two).
 *  5. Cap the total length so a malformed comment can't overwhelm the popup.
 *
 * Escaping happens *before* substitution so the regex-based link rewriter sees stable input. The
 * substitution patterns only emit tags we just escaped against (`<`, `>`, `"`), so the output is
 * safe to drop straight into IntelliJ's `CONTENT_START` / `CONTENT_END` block.
 */
object JsDocHtmlFormatter {

    private const val MAX_LENGTH = 8000

    private val fenceRegex = Regex("```[\\s\\S]*?```", RegexOption.MULTILINE)
    private val linkRegex = Regex("""\[([^\]]+?)]\((https?://[^)\s]+)\)""")
    private val codeRegex = Regex("`([^`\n]+?)`")
    private val boldRegex = Regex("""\*\*([^*\n]+?)\*\*""")
    private val italicStarRegex = Regex("""(?<![*\w])\*([^*\n]+?)\*(?![*\w])""")
    private val italicUnderscoreRegex = Regex("""(?<![_\w])_([^_\n]+?)_(?![_\w])""")
    private val mdnLineLower = "[mdn reference]"

    fun format(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        s = fenceRegex.replace(s, "")
        s = s.replace("js-nolint", "", ignoreCase = true)
        if (s.isBlank()) return null

        val escaped = escapeHtml(s)
        // Collapse before markdown substitution so the dedup operates on the original
        // `[MDN Reference](url)` form, not the converted `<a>` tag.
        val collapsed = collapseRepeatedMdn(escaped)
        val converted = applyMarkdown(collapsed)
        val truncated = if (converted.length > MAX_LENGTH) converted.take(MAX_LENGTH) + "\n…" else converted
        return truncated.ifBlank { null }
    }

    private fun applyMarkdown(escaped: String): String {
        var out = escaped
        out = linkRegex.replace(out) { m ->
            val text = m.groupValues[1]
            val url = m.groupValues[2]
            """<a href="$url">$text</a>"""
        }
        out = codeRegex.replace(out) { m -> "<code>${m.groupValues[1]}</code>" }
        out = boldRegex.replace(out) { m -> "<b>${m.groupValues[1]}</b>" }
        out = italicStarRegex.replace(out) { m -> "<i>${m.groupValues[1]}</i>" }
        out = italicUnderscoreRegex.replace(out) { m -> "<i>${m.groupValues[1]}</i>" }
        return out
    }

    private fun collapseRepeatedMdn(text: String): String {
        val lines = text.lines().map { it.trimEnd() }
        val out = ArrayList<String>(lines.size)
        var prevWasMdn = false
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) {
                if (out.isEmpty() || out.last().isEmpty()) continue
                out.add(t)
                prevWasMdn = false
                continue
            }
            val isMdn = t.lowercase().contains(mdnLineLower) || t.lowercase().startsWith("mdn reference")
            if (isMdn && prevWasMdn) continue
            out.add(t)
            prevWasMdn = isMdn
        }
        return out.joinToString("\n").trim()
    }

    /**
     * Escapes the three HTML metacharacters the documentation popup actually parses.
     * Exposed (module-private) so other documentation formatters can share the same routine.
     */
    internal fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
