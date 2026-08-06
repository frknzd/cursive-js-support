package com.cursivejssupport.index

import com.cursivejssupport.parser.ParsedSymbols
import com.cursivejssupport.project.CljsRuntimeTarget
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Smoke tests against the bundled `node-symbols.json.gz`: the `process` global must be present
 * and tagged with the NODE environment, the `fs` ambient module must be exposed as an npm
 * package, and `node:`-prefixed lookups must normalize to the unprefixed package.
 */
class BundledNodeIndexTest {

    companion object {
        private lateinit var index: JsSymbolIndex

        @JvmStatic
        @BeforeClass
        fun load() {
            val file = File("src/main/resources/js/node-symbols.json.gz")
            assumeTrue("bundled node index missing", file.isFile)
            val symbols = GZIPInputStream(file.inputStream()).use {
                jacksonObjectMapper().readValue<ParsedSymbols>(it)
            }
            index = JsSymbolIndex()
            index.load(symbols)
            index.setLoaded(true)
        }
    }

    @Test fun `process global is tagged NODE`() {
        val info = index.resolveGlobalInfo("process")
        assertNotNull("process should be in the node index", info)
        assertEquals(JsEnvironment.NODE, JsEnvironment.fromWire(info!!.environment))
    }

    @Test fun `fs ambient module is exposed as an npm package`() {
        assertTrue("fs should be a known npm export package", index.npmExportNames("fs").isNotEmpty())
        // node: prefix normalization: node:fs resolves to the same exports as fs.
        assertEquals(index.npmExportNames("fs").toSet(), index.npmExportNames("node:fs").toSet())
    }

    @Test fun `isKnownNpmExport normalizes node prefix`() {
        // `readFileSync` is a named export of the fs ambient module.
        assertTrue("readFileSync should be a known fs export", index.isKnownNpmExport("fs", "readFileSync"))
        assertTrue("node:fs should normalize to fs", index.isKnownNpmExport("node:fs", "readFileSync"))
    }

    @Test fun `process is visible only for node-targeted files`() {
        // Browser-only target must NOT see the node `process` global.
        val browserOnly = JsEnvironment.visibleForTargets(setOf(CljsRuntimeTarget.BROWSER))
        assertTrue(JsEnvironment.NODE !in browserOnly)
        // Node-targeted files see NODE (and COMMON).
        val nodeTarget = JsEnvironment.visibleForTargets(setOf(CljsRuntimeTarget.NODE))
        assertTrue(JsEnvironment.NODE in nodeTarget)
        // Unknown / mixed (empty) target sees everything.
        val unknown = JsEnvironment.visibleForTargets(emptySet())
        assertTrue(JsEnvironment.NODE in unknown)
    }

    @Test fun `bun target implicitly sees node symbols`() {
        // Bun builds can require node: built-ins, so NODE symbols must be visible there too.
        val bunTarget = JsEnvironment.visibleForTargets(setOf(CljsRuntimeTarget.BUN))
        assertTrue(JsEnvironment.NODE in bunTarget)
        assertTrue(JsEnvironment.BUN in bunTarget)
    }
}
