package com.cursivejssupport.npm

/**
 * Well-known Node.js and Bun built-in module names offered in `(:require ["…" :as …])` completion
 * and accepted by [NpmPackageResolver.discoverAllDependencyPackageNames] even when no `node_modules`
 * entry exists for them. The bundled node/bun symbol indexes register the matching ambient modules
 * (`declare module "fs" {…}`) as packages, so member completion resolves against them.
 *
 * Node accepts both the bare name (`"fs"`) and the `node:`-prefixed form (`"node:fs"`); subpaths
 * such as `fs/promises` and `node:fs/promises` are also first-class. Bun additionally exposes
 * `bun:*` modules.
 */
object JsBuiltInModules {

    /** Core Node.js built-in module basenames (Node 20+). */
    val nodeBasenames: List<String> = listOf(
        "assert", "async_hooks", "buffer", "child_process", "cluster", "console", "constants",
        "crypto", "dgram", "diagnostics_channel", "dns", "domain", "events", "fs", "http",
        "http2", "https", "inspector", "module", "net", "os", "path", "perf_hooks", "process",
        "punycode", "querystring", "readline", "repl", "stream", "string_decoder", "sys",
        "test", "timers", "tls", "trace_events", "tty", "url", "util", "v8", "vm", "wasi",
        "worker_threads", "zlib",
    )

    /** Subpaths that exist for some Node built-ins (e.g. `fs/promises`, `stream/web`, `stream/consumers`). */
    val nodeSubpaths: List<String> = listOf(
        "fs/promises", "stream/consumers", "stream/promises", "stream/web", "timers/promises",
        "readline/promises", "util/types", "path/posix", "path/win32", "dns/promises",
        "readline", "test", "assert/strict", "module",
    )

    /** Bun-specific built-in modules (the `bun:` prefix is part of the name). */
    val bunModules: List<String> = listOf(
        "bun", "bun:sqlite", "bun:test", "bun:ffi", "bun:jsc", "bun:wrap",
    )

    /** All Node built-in require strings: bare names, `node:`-prefixed, and subpaths. */
    val nodeRequireStrings: List<String> = buildList {
        for (name in nodeBasenames) {
            add(name)
            add("node:$name")
        }
        for (sub in nodeSubpaths) {
            add(sub)
            add("node:$sub")
        }
    }

    /** Every built-in require string (Node + Bun), used for package-string completion. */
    val allRequireStrings: List<String> = nodeRequireStrings + bunModules

    /** Normalize a require string for index lookups: `node:fs` → `fs`, leaving bare names unchanged. */
    fun normalizeForLookup(name: String): String =
        name.removePrefix("node:")
}
