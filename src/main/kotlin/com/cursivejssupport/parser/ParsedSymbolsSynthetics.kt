package com.cursivejssupport.parser

/**
 * `globalThis` is a TypeScript keyword rather than a declaration — no lib `.d.ts` carries a
 * `declare var globalThis`, so the extractor never emits it and `js/globalThis` looks unknown to
 * the index. lib.dom types it exactly like `window` / `self` (`Window & typeof globalThis`), so
 * mirror whichever of those the index carries: same type, same declaration site (goto-declaration
 * lands on the line that spells the type out), own doc.
 */
fun ParsedSymbols.withSyntheticGlobalThis(): ParsedSymbols {
    if (variables.containsKey("globalThis")) return this
    val model = variables["window"] ?: variables["self"] ?: return this
    val globalThis = JsVariableInfo(type = model.type, doc = GLOBAL_THIS_DOC, location = model.location)
    return copy(variables = variables + ("globalThis" to globalThis))
}

private const val GLOBAL_THIS_DOC =
    "The **`globalThis`** global property contains the global `this` value, which is usually akin " +
        "to the global object.\n\n[MDN Reference](https://developer.mozilla.org/docs/Web/JavaScript/" +
        "Reference/Global_Objects/globalThis)"
