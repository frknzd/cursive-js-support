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

        // Start from the global named by the first segment.
        var currentType = index.resolveGlobalType(parts[0]) ?: return false

        // Walk intermediate segments. Each must be a property — methods cannot be chained through
        // with dot syntax in ClojureScript (js/document.getSelection.removeAllRanges is wrong;
        // (.. js/document getSelection removeAllRanges) is the correct form).
        for (part in parts.drop(1).dropLast(1)) {
            val member = index.resolveMember(currentType, part)?.first ?: return false
            if (member.kind == "method") return false
            currentType = index.canonicalType(member.type)
            if (currentType.isEmpty() || currentType == "any" || currentType == "void") return false
        }

        return index.resolveMember(currentType, parts.last()) != null
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
