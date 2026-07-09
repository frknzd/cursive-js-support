package com.cursivejssupport.documentation

/**
 * Splits an extractor-produced JSDoc string into its untagged body and structured tag sections.
 *
 * The dts extractor emits each JSDoc block/tag as its own `\n\n`-separated paragraph, with tags
 * prefixed by their name: `@param input the request input`, `@deprecated use X`, `@example …`.
 * Legacy index entries carry no tag prefixes and parse as pure body text.
 */
data class ParsedJsDoc(
    val body: String?,
    /** `@param` descriptions keyed by parameter name. */
    val params: Map<String, String>,
    /** `@returns` / `@return` description. */
    val returns: String?,
    val examples: List<String>,
    val throws: List<String>,
    val sees: List<String>,
    /** Non-null when `@deprecated` is present; the value is the (possibly empty) reason. */
    val deprecated: String?,
)

object JsDocTags {

    private val tagRegex = Regex("""^@(\w+)[ \t]?([\s\S]*)$""")

    fun parse(raw: String?): ParsedJsDoc {
        if (raw.isNullOrBlank()) {
            return ParsedJsDoc(null, emptyMap(), null, emptyList(), emptyList(), emptyList(), null)
        }
        val bodyParts = ArrayList<String>()
        val params = LinkedHashMap<String, String>()
        var returns: String? = null
        val examples = ArrayList<String>()
        val throws = ArrayList<String>()
        val sees = ArrayList<String>()
        var deprecated: String? = null

        for (part in raw.split("\n\n")) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val m = tagRegex.find(p)
            if (m == null) {
                bodyParts.add(p)
                continue
            }
            val content = m.groupValues[2].trim()
            when (m.groupValues[1].lowercase()) {
                "param" -> {
                    val name = content.takeWhile { !it.isWhitespace() }
                    val desc = content.drop(name.length).trim()
                    if (name.isNotEmpty()) params[name] = desc
                }
                "returns", "return" -> returns = content.ifEmpty { returns }
                "example" -> if (content.isNotEmpty()) examples.add(content)
                "throws", "exception" -> if (content.isNotEmpty()) throws.add(content)
                "see" -> if (content.isNotEmpty()) sees.add(content)
                "deprecated" -> deprecated = content
                else -> bodyParts.add(p)  // unknown tag: keep visible rather than lose it
            }
        }
        return ParsedJsDoc(
            body = bodyParts.joinToString("\n\n").ifBlank { null },
            params = params,
            returns = returns,
            examples = examples,
            throws = throws,
            sees = sees,
            deprecated = deprecated,
        )
    }
}
