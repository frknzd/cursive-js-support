package com.cursivejssupport.documentation

import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsVariableInfo

/**
 * A resolved description of "what is this symbol?" for documentation rendering. Mirrors the
 * completion-side [com.cursivejssupport.completion.InteropCompletionContext] so the doc and
 * completion pipelines stay symmetric.
 *
 * Every variant carries enough information to format a definition line, body, and parameter /
 * return / link sections without re-querying the symbol index.
 */
sealed interface InteropDocSubject {

    /** `js/<name>` resolving to a typed global (e.g. `js/document` → `Document`). */
    data class JsGlobal(
        val name: String,
        val info: JsVariableInfo,
    ) : InteropDocSubject

    /** `js/<name>` resolving to a function (or a function-shaped global). */
    data class JsFunction(
        val name: String,
        val overload: JsMember,
        val overloadCount: Int = 1,
    ) : InteropDocSubject

    /**
     * `js/<chain>` where the final segment is a member walk through interface members.
     * [declaringType] is the interface that exposes [member]; [segments] is the original chain.
     */
    data class JsChain(
        val segments: List<String>,
        val declaringType: String,
        val member: JsMember,
    ) : InteropDocSubject

    /**
     * `.method` / `.-prop` head-position member access. [declaringType] is the human-readable
     * interface name (TYPE$ sanitization already applied) and [member] is the resolved overload.
     */
    data class Member(
        val name: String,
        val asProperty: Boolean,
        val declaringType: String,
        val member: JsMember,
    ) : InteropDocSubject

    /** `<alias>/<export>` resolved to a known npm export. */
    data class NpmExport(
        val packageName: String,
        val exportName: String,
        val type: String?,
    ) : InteropDocSubject

    /** A bare alias symbol (e.g. `react` when an alias resolves to `["react"]`). */
    data class NpmAlias(
        val alias: String,
        val packageName: String,
    ) : InteropDocSubject

    /** Nothing resolved. The provider should return `null`. */
    object Unknown : InteropDocSubject
}
