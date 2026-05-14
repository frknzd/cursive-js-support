package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex

/**
 * Shared “does this resolve in our JS index?” checks for highlight suppression and tests.
 */
object JsInteropIndexQueries {

    /** `rest` is the substring after `js/` (may contain dots). */
    fun isResolvableDottedJsRest(index: JsSymbolIndex, rest: String): Boolean {
        if (!index.isLoaded || !rest.contains('.')) return false
        val parts = rest.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        val parentType = index.resolveJsChainType(parts.dropLast(1)) ?: return false
        val last = parts.last()
        return index.resolveMember(parentType, last) != null
    }

    /** Full editor symbol text and optional PSI namespace/name (e.g. `js/document.x` or ns=`js`, name=`document.x`). */
    fun isResolvableJsInteropSymbol(index: JsSymbolIndex, fullText: String, namespace: String?, name: String?): Boolean {
        if (!index.isLoaded) return false
        val seg = JsInteropChain.segmentsFromSymbol(namespace, name, fullText) ?: return false
        if (seg.isEmpty()) return false
        return when {
            seg.size == 1 -> index.isKnownGlobal(seg[0])
            else -> {
                val parentType = index.resolveJsChainType(seg.dropLast(1)) ?: return false
                val last = seg.last()
                index.resolveMember(parentType, last) != null
            }
        }
    }
}
