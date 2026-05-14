package com.cursivejssupport.completion

import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.completion.PrefixMatcher

/**
 * Prefix matching for `js/receiver.member` lookups when the typed prefix still includes a partial member
 * (e.g. `js/document.cre` must match lookup `js/document.createRange`).
 */
class JsInteropChainPrefixMatcher private constructor(
    prefix: String,
    private val chainPrefix: String,
    private val memberPrefix: String,
) : PrefixMatcher(prefix) {

    override fun prefixMatches(name: String): Boolean {
        if (!name.startsWith("js/")) return false
        if (memberPrefix.isEmpty()) {
            return name.startsWith(chainPrefix, ignoreCase = true)
        }
        if (!name.startsWith(chainPrefix, ignoreCase = true)) return false
        val rest = name.substring(chainPrefix.length)
        return rest.startsWith(memberPrefix, ignoreCase = true)
    }

    override fun cloneWithPrefix(prefix: String): PrefixMatcher =
        fromEffectiveJs(prefix) ?: CaseInsensitivePlainPrefixMatcher(prefix)

    companion object {
        fun fromEffectiveJs(eff: String): JsInteropChainPrefixMatcher? {
            val cleaned = eff
                .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
                .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
                .trim()
            if (!cleaned.startsWith("js/")) return null
            val body = cleaned.removePrefix("js/").trimEnd('.')
            if (body.isEmpty()) {
                return JsInteropChainPrefixMatcher(cleaned, "js/", "")
            }
            val lastDot = body.lastIndexOf('.')
            return if (lastDot < 0) {
                JsInteropChainPrefixMatcher(cleaned, "js/$body", "")
            } else {
                val receiver = body.substring(0, lastDot)
                val member = body.substring(lastDot + 1)
                val chainPrefix = "js/$receiver."
                JsInteropChainPrefixMatcher(cleaned, chainPrefix, member)
            }
        }
    }
}

/** Fallback when [JsInteropChainPrefixMatcher.fromEffectiveJs] does not apply. */
internal class CaseInsensitivePlainPrefixMatcher(prefix: String) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String): Boolean = name.startsWith(prefix, ignoreCase = true)

    override fun cloneWithPrefix(prefix: String): PrefixMatcher = CaseInsensitivePlainPrefixMatcher(prefix)
}
