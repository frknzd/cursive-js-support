package com.cursivejssupport.completion

import com.cursivejssupport.npm.NpmBinding

/**
 * The single, document-derived description of "what is the user trying to complete?".
 *
 * Every variant carries [prefix] (the substring of typed text to feed into the lookup's
 * [com.intellij.codeInsight.completion.PrefixMatcher]) and [replacementStart] (the absolute
 * offset where the lookup's replacement should begin). Lookup elements always insert *short*
 * names — the prefix matcher handles backward replacement so the receiver / leading punctuation
 * stays intact.
 */
sealed interface InteropCompletionContext {
    val prefix: String
    val replacementStart: Int

    /** Nothing interesting near the caret. */
    object None : InteropCompletionContext {
        override val prefix: String = ""
        override val replacementStart: Int = -1
    }

    /** `(js/<prefix>` — suggest globals (variables + functions). */
    data class JsGlobalName(
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /**
     * `(js/document.<prefix>` or `(js/console.log.<prefix>` — resolve the chain through
     * [com.cursivejssupport.index.JsSymbolIndex.resolveJsChainType] then offer its members.
     */
    data class JsChainMember(
        val receiverSegments: List<String>,
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /**
     * Head position dot interop: `(.member receiver ...)` or `(.-prop receiver ...)`.
     *
     * [asProperty] is `true` for the property form (`.-`). The contributor resolves the receiver
     * via [com.cursivejssupport.util.JsResolveUtil] (PSI-driven, since the receiver may be any
     * Clojure expression) and emits matching members.
     */
    data class DotMember(
        val asProperty: Boolean,
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /** Inside the npm package string of `(ns ... (:require ["<partial>"]))`. */
    data class NsRequirePackage(
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /** Inside `[goog.<partial>]` in `(:require ...)` — suggest known goog namespace names. */
    data class GoogNamespaceRequire(
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /**
     * `goog` or `goog.<partial>` typed in code (no slash yet) — complete the full goog
     * namespace name, e.g. `goog.str` → `goog.string`, `goog.dom`.
     *
     * Distinct from [GoogNamespaceRequire] which fires inside `(:require ...)` forms.
     * [prefix] is the full typed token (e.g. `"goog.str"`); the prefix matcher filters
     * all known namespace names that start with it, and replacement covers the whole token.
     */
    data class GoogNamespaceName(
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /**
     * Inside `(:require ["pkg" :refer [<partial>])` (or `:rename {<partial>` … }). Only the
     * package's exports are valid here — the keyword helpers (`:as`, `:refer`, `:rename`,
     * `:default`) belong outside this vector and are surfaced via [NsRequireKeyword].
     */
    data class NsRefer(
        val packageName: String,
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /**
     * Inside an `[" pkg" <pos>]` slot where the user is right after the closing `"` or `:as`
     * keyword and should be offered `:as`, `:refer`, `:rename`, `:default`.
     */
    data class NsRequireKeyword(
        val packageName: String,
        val availableKeywords: List<String>,
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /** `(<Alias>/<prefix>` — suggest exports of the npm package the alias resolves to. */
    data class NpmAliasExport(
        val alias: String,
        val packageName: String,
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /**
     * `(<Alias>/<Export>.<receiver>.<prefix>` — walk the npm export's TypeScript type through
     * the receiver chain, then suggest its members.
     */
    data class NpmAliasExportMember(
        val alias: String,
        val packageName: String,
        val exportName: String,
        val receiverSegments: List<String>,
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /** `<prefix>` — suggest npm aliases themselves. */
    data class NpmAliasName(
        val availableAliases: Map<String, NpmBinding>,
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext

    /**
     * Inside `(.. root step1 step2 <prefix>)` — a Clojure `..` chain-access form.
     *
     * [priorChain] holds all complete tokens between `..` and the caret:
     * - index 0 is the root (`js/document`, `alias/Export`, bare global)
     * - indices 1+ are the prior chain steps (raw token text: `method`, `-property`)
     *
     * [prefix] is the partial step text as typed: bare for methods (`"cre"`), with a
     * leading `-` for properties (`"-inn"`). Property lookup strings are also prefixed
     * with `-` so `PlainPrefixMatcher` filters correctly against them.
     */
    data class DotDotForm(
        val priorChain: List<String>,
        override val prefix: String,
        override val replacementStart: Int,
    ) : InteropCompletionContext
}
