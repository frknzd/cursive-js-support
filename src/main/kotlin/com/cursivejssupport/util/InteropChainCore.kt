package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex

/**
 * The chain macros whose member-access steps the plugin understands.
 *
 * Per-macro semantics (load-bearing — `->>` is NOT `->`):
 *
 * | Macro            | Step shapes                     | Receiver of step k                   |
 * |------------------|---------------------------------|--------------------------------------|
 * | `..`             | `name`, `-name`, `(name args…)` | type after steps 0..k-1              |
 * | `->` / `some->`  | `.name`, `.-name`, `(.name a…)` | type after steps 0..k-1              |
 * | `->>` / `some->>`| same, but the threaded value is | the step's own FIRST argument for    |
 * |                  | the LAST argument of list steps | list steps; threaded type for bare   |
 * | `doto`           | `.name`, `(.name a…)`           | always the root's type               |
 * | `cond->` family  | `test step` pairs               | root's type (not confident)          |
 */
enum class ChainKind(val headText: String, val typeFlows: Boolean = true) {
    DOT_DOT(".."),
    THREAD_FIRST("->"),
    THREAD_LAST("->>"),
    DOTO("doto"),
    SOME_FIRST("some->"),
    SOME_LAST("some->>"),
    COND_FIRST("cond->", typeFlows = false),
    COND_LAST("cond->>", typeFlows = false);

    /** Steps use bare `name` / `-name` (no leading dot) — only true for `..`. */
    val bareSteps: Boolean get() = this == DOT_DOT

    /** The threaded value becomes the LAST argument of list steps (`->>` family). */
    val threadsLast: Boolean get() = this == THREAD_LAST || this == SOME_LAST || this == COND_LAST

    /** Every step receives the root value — steps do not feed each other (`doto`, `cond->`). */
    val rootReceiver: Boolean get() = this == DOTO || isConditional

    /** Elements after the root come in `test step` pairs (`cond->` family). */
    val isConditional: Boolean get() = this == COND_FIRST || this == COND_LAST

    companion object {
        private val byHead = entries.associateBy { it.headText }
        fun fromHead(head: String?): ChainKind? = head?.let { byHead[it] }
    }
}

/** One parsed member-access step of a chain form. */
data class ChainStepSpec(
    val memberName: String,
    val isProperty: Boolean,
    val isListStep: Boolean = false,
)

/**
 * Pure token-level chain-step parsing and type advancement, shared by the PSI facade
 * ([InteropChains]) and the document-text completion detector (which must stay PSI-free).
 */
object InteropChainCore {

    /**
     * Parses a raw step token into a member spec, or null when the step is not a member access
     * for the given macro (e.g. `(str …)` inside `->`). Handles both bare tokens
     * (`createRange`, `-body`, `.getMonth`, `.-length`) and whole list-step text
     * (`"(.setAttribute \"x\" \"1\")"`) as collected by the backward document scanner.
     */
    fun parseStepToken(raw: String, kind: ChainKind): ChainStepSpec? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("(")) {
            val inner = t.removePrefix("(").trimStart()
            val head = inner.takeWhile { !it.isWhitespace() && it != ')' }
            return parseStepHead(head, kind)?.copy(isListStep = true)
        }
        return parseStepHead(t, kind)
    }

    /**
     * Parses the member-carrying token of a step: the bare step itself, or a list step's head.
     * `..` steps are bare `name` / `-name` (a leading `.` is tolerated); threading/doto steps
     * require a leading `.` (`.name` / `.-name`) — anything else is not a member access.
     */
    fun parseStepHead(head: String, kind: ChainKind): ChainStepSpec? {
        val t = head.trim()
        if (t.isEmpty()) return null
        if (kind.bareSteps) {
            val stripped = if (t.startsWith("..")) return null else t.removePrefix(".")
            val isProperty = stripped.startsWith("-")
            val name = stripped.removePrefix("-")
            if (!isMemberName(name)) return null
            return ChainStepSpec(name, isProperty)
        }
        if (!t.startsWith(".") || t.startsWith("..")) return null
        val isProperty = t.startsWith(".-")
        val name = t.removePrefix(".").removePrefix("-")
        if (!isMemberName(name)) return null
        return ChainStepSpec(name, isProperty)
    }

    /**
     * Advances one member-access step through the index: resolves [memberName] on [currentType]
     * and returns the canonical type of the member's value (method return / property type).
     * Returns null when the member is unknown or the resulting type carries no member info.
     */
    fun advance(currentType: String, memberName: String, index: JsSymbolIndex): String? {
        val member = index.resolveMember(currentType, memberName)?.first ?: return null
        val next = index.memberValueTypeName(member)
        if (next.isEmpty() || next == "any" || next == "void" || next == "undefined") return null
        return next
    }

    private fun isMemberName(name: String): Boolean =
        name.isNotEmpty() && name.none { it.isWhitespace() || it in "()[]{}\"';@#/" }
}
