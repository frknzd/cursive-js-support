package com.cursivejssupport.index

import java.util.EnumSet

/**
 * Runtime environment a JavaScript symbol belongs to.
 *
 * Used to filter completion/hover so that, for example, `js/process` is only offered
 * when the requiring `.cljs` file is part of a Node-targeted build, while `js/document`
 * is offered for browser-targeted builds. [COMMON] covers ECMAScript built-ins that are
 * available everywhere (e.g. `js/Promise`, `js/JSON`) and npm packages whose target is
 * not statically known.
 */
enum class JsEnvironment(
    /** Stable wire key used by `dts-extractor-runner.js` and the bundled index files. */
    val wire: String,
    /** Short label shown in hover headers and completion tail badges. */
    val badge: String,
) {
    BROWSER("browser", "Browser"),
    NODE("node", "Node.js"),
    BUN("bun", "Bun"),
    DENO("deno", "Deno"),
    COMMON("common", "ECMAScript");

    companion object {
        /**
         * Parse the wire string emitted by the extractor / stored in index files.
         * Unknown or null values map to [COMMON] so legacy indexes keep working.
         */
        fun fromWire(wire: String?): JsEnvironment =
            wire?.let { w -> entries.firstOrNull { it.wire.equals(w, ignoreCase = true) } } ?: COMMON

        /**
         * Environments whose symbols are visible for a file with the given runtime targets.
         * [COMMON] (ECMAScript built-ins) is always included. When [targets] is empty (no build
         * profile covers the file) every environment is returned so unknown/mixed files see all
         * symbols rather than none.
         */
        fun visibleForTargets(targets: Collection<com.cursivejssupport.project.CljsRuntimeTarget>): Set<JsEnvironment> {
            if (targets.isEmpty()) return entries.toSet()
            val out = EnumSet.of(COMMON)
            for (t in targets) when (t) {
                com.cursivejssupport.project.CljsRuntimeTarget.BROWSER -> out.add(BROWSER)
                com.cursivejssupport.project.CljsRuntimeTarget.NODE -> { out.add(NODE) }
                com.cursivejssupport.project.CljsRuntimeTarget.BUN -> { out.add(BUN); out.add(NODE) }
                com.cursivejssupport.project.CljsRuntimeTarget.DENO -> out.add(DENO)
            }
            return out
        }
    }
}
