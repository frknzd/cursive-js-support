package com.cursivejssupport.npm

enum class NpmBindingKind { AS, DEFAULT, ALL, REFER, RELATIVE }

/**
 * Describes how a local name is bound to an npm package or a relative JS module in a
 * shadow-cljs/cljs.main `(:require ...)` form.
 *
 * [exportName] is only non-null for REFER bindings: it holds the actual export name from the
 * package (which may differ from the local alias when :rename is used, e.g. {:rename {orig my}}
 * gives the alias "my" an exportName of "orig").
 *
 * For [NpmBindingKind.RELATIVE] bindings, [packageName] is the raw require string (e.g.
 * `"./capture_util.js"`) and [relativeFilePath] is the resolved absolute path of the target
 * JS file (when resolution succeeded) so semantic/goto can jump straight to it.
 */
data class NpmBinding(
    val packageName: String,
    val kind: NpmBindingKind,
    val exportName: String? = null,
    val relativeFilePath: String? = null,
)
