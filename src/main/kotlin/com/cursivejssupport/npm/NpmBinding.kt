package com.cursivejssupport.npm

enum class NpmBindingKind { AS, DEFAULT, ALL, REFER }

/**
 * Describes how a local name is bound to an npm package in a shadow-cljs (:require ...) form.
 *
 * [exportName] is only non-null for REFER bindings: it holds the actual export name from the
 * package (which may differ from the local alias when :rename is used, e.g. {:rename {orig my}}
 * gives the alias "my" an exportName of "orig").
 */
data class NpmBinding(
    val packageName: String,
    val kind: NpmBindingKind,
    val exportName: String? = null,
)
