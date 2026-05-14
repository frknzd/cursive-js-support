package com.cursivejssupport.documentation

/**
 * Normalizes JSDoc / MDN-heavy text for IntelliJ documentation popups.
 */
object JsDocHtmlFormatter {

    private val fenceRegex = Regex("```[\\s\\S]*?```", RegexOption.MULTILINE)

    fun format(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        s = fenceRegex.replace(s, "")
        s = s.replace("js-nolint", "", ignoreCase = true)
        val lines = s.lines().map { it.trimEnd() }
        val collapsed = ArrayList<String>()
        var prevKey: String? = null
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val key = t.lowercase()
            if (key == prevKey && (key.startsWith("[mdn reference]") || key.startsWith("mdn reference"))) {
                continue
            }
            collapsed.add(t)
            prevKey = key
        }
        s = collapsed.joinToString("\n").trim()
        if (s.length > 8000) {
            s = s.take(8000) + "\n…"
        }
        return s.ifBlank { null }
    }
}
