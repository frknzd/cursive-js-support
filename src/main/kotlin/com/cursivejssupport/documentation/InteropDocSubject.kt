package com.cursivejssupport.documentation

import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsVariableInfo

/**
 * A resolved description of "what is this symbol?" for documentation rendering. Mirrors the
 * completion-side [com.cursivejssupport.completion.InteropCompletionContext] so the doc and
 * completion pipelines stay symmetric.
 *
 * Every variant carries enough information to format a definition line, body, and parameter /
 * return / link sections without re-querying the symbol index. Variants with callable members
 * carry the FULL overload list; the first entry is the primary (kind-preferred) overload.
 */
sealed interface InteropDocSubject {

    /** `js/<name>` resolving to a typed global (e.g. `js/document` → `Document`). */
    data class JsGlobal(
        val name: String,
        val info: JsVariableInfo,
        val isConstructor: Boolean = false,
    ) : InteropDocSubject

    /** `js/<name>` resolving to a function (or a function-shaped global). */
    data class JsFunction(
        val name: String,
        val overload: JsMember,
        val overloads: List<JsMember> = listOf(overload),
    ) : InteropDocSubject

    /**
     * `js/<chain>` where the final segment is a member walk through interface members.
     * [declaringType] is the interface that exposes [member]; [segments] is the original chain.
     */
    data class JsChain(
        val segments: List<String>,
        val declaringType: String,
        val member: JsMember,
        val overloads: List<JsMember> = listOf(member),
    ) : InteropDocSubject

    /** One non-primary declaring interface of an ambiguous member, with its overloads. */
    data class MemberGroup(
        val declaringType: String,
        val overloads: List<JsMember>,
    )

    /**
     * `.method` / `.-prop` head-position member access. [declaringType] is the human-readable
     * interface name (TYPE$ sanitization already applied) and [member] is the primary overload.
     * [receiverText] is the receiver expression as written (`doc`, `js/document`) when short
     * enough to reuse in the CLJS call-signature line.
     */
    data class Member(
        val name: String,
        val asProperty: Boolean,
        val declaringType: String,
        val member: JsMember,
        val overloads: List<JsMember> = listOf(member),
        val receiverText: String? = null,
        /** Other interfaces also declaring [name] when the receiver type is unknown; empty ⇒ confident. */
        val alternatives: List<MemberGroup> = emptyList(),
    ) : InteropDocSubject

    /** Constructor call `(Foo. …)` / `(js/Foo. …)` — [overloads] are the `new` signatures. */
    data class Constructor(
        val typeName: String,
        val overloads: List<JsMember> = emptyList(),
        val doc: String? = null,
    ) : InteropDocSubject

    /** `<alias>/<export>` resolved to a known npm export. */
    data class NpmExport(
        val packageName: String,
        val exportName: String,
        val type: String?,
        /** Function-shaped export signatures when the typings carried them (also goog fns). */
        val overloads: List<JsMember> = emptyList(),
        val doc: String? = null,
    ) : InteropDocSubject

    /** A bare alias symbol (e.g. `react` when an alias resolves to `["react"]`). */
    data class NpmAlias(
        val alias: String,
        val packageName: String,
    ) : InteropDocSubject

    /** Nothing resolved. The provider should return `null`. */
    object Unknown : InteropDocSubject
}
