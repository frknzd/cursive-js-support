package com.cursivejssupport.index

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * `JsSymbolIndex.publish` swaps the whole index, so a rebuild that loads fewer symbol sets than
 * the initial load silently takes symbols away from the editor mid-session — the shape of the bug
 * where a `package.json` save cost the project `js/process` and the `fs` / `path` module exports.
 *
 * Every rebuild path therefore goes through [JsIndexLoader.loadAllBundled], and this pins what
 * that has to leave behind: one landmark per bundled set.
 */
class BundledIndexCompletenessTest {

    companion object {
        private lateinit var index: JsSymbolIndex

        @JvmStatic
        @BeforeClass
        fun load() {
            assumeTrue(
                "bundled indexes missing",
                File("src/main/resources/js/node-symbols.json.gz").isFile,
            )
            index = JsSymbolIndex()
            JsIndexLoader.loadAllBundled(index)
            index.setLoaded(true)
        }
    }

    @Test fun `browser globals are indexed`() {
        assertTrue("js/document should be a known global", index.isKnownGlobal("document"))
    }

    @Test fun `goog namespaces are indexed`() {
        assertTrue("goog namespaces should be indexed", index.getGoogNamespaceNames().isNotEmpty())
    }

    @Test fun `node globals and ambient modules are indexed`() {
        assertTrue("js/process should be a known global", index.isKnownGlobal("process"))
        assertTrue("fs should export mkdirSync", index.isKnownNpmExport("fs", "mkdirSync"))
        assertTrue("path should export join", index.isKnownNpmExport("path", "join"))
    }

    @Test fun `bun globals are indexed`() {
        assertTrue("js/Bun should be a known global", index.isKnownGlobal("Bun"))
    }

    @Test fun `deno globals are indexed`() {
        assertTrue("js/Deno should be a known global", index.isKnownGlobal("Deno"))
    }
}
