package com.cursivejssupport.util

/**
 * Parses dotted JavaScript interop chains such as `js/document.createRange` into segments.
 */
object JsInteropChain {

    fun segmentsFromFullText(text: String): List<String>? {
        val t = text.trim()
        if (!t.startsWith("js/")) return null
        val rest = t.removePrefix("js/").trimEnd('.')
        if (rest.isEmpty()) return emptyList()
        return rest.split('.').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * @param fullText raw symbol text (e.g. `js/document.createRange`)
     * @param namespace PSI namespace (e.g. `js`)
     * @param name PSI name after namespace (e.g. `document.createRange`)
     */
    fun segmentsFromSymbol(namespace: String?, name: String?, fullText: String): List<String>? {
        when {
            fullText.trim().startsWith("js/") -> return segmentsFromFullText(fullText)
            namespace == "js" && !name.isNullOrBlank() ->
                return name.split('.').map { it.trim() }.filter { it.isNotEmpty() }
            else -> return null
        }
    }
}
