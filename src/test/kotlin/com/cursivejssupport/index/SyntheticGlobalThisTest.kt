package com.cursivejssupport.index

import com.cursivejssupport.parser.JsLocation
import com.cursivejssupport.parser.JsVariableInfo
import com.cursivejssupport.parser.ParsedSymbols
import com.cursivejssupport.parser.withSyntheticGlobalThis
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * `globalThis` is a TypeScript keyword, so no lib `.d.ts` declares it and the extractor cannot
 * emit it — [withSyntheticGlobalThis] mirrors the `window` declaration instead. These tests pin
 * both the transform's shape and the behaviour it buys against the real bundled index.
 */
class SyntheticGlobalThisTest {

    companion object {
        private lateinit var bundled: ParsedSymbols
        private lateinit var index: JsSymbolIndex

        @JvmStatic
        @BeforeClass
        fun load() {
            val file = File("src/main/resources/js/browser-symbols.json.gz")
            assumeTrue("bundled index missing", file.isFile)
            bundled = GZIPInputStream(file.inputStream()).use {
                jacksonObjectMapper().readValue<ParsedSymbols>(it)
            }
            index = JsSymbolIndex()
            index.load(bundled.withSyntheticGlobalThis())
            index.setLoaded(true)
        }
    }

    // ─── transform ──────────────────────────────────────────────────────────────

    @Test fun `the extractor does not emit globalThis on its own`() {
        assertNull(bundled.variables["globalThis"])
    }

    @Test fun `synthetic globalThis mirrors the window declaration`() {
        val window = bundled.variables.getValue("window")
        val globalThis = bundled.withSyntheticGlobalThis().variables.getValue("globalThis")
        assertEquals(window.type, globalThis.type)
        assertEquals(window.location, globalThis.location)
        assertTrue("doc should be globalThis's own", globalThis.doc.orEmpty().contains("globalThis"))
    }

    @Test fun `a real globalThis declaration wins over the synthetic one`() {
        val declared = JsVariableInfo(type = "Foo", doc = "real", location = JsLocation("f.d.ts", 1))
        val symbols = ParsedSymbols(
            variables = mapOf("window" to JsVariableInfo(type = "Window&any"), "globalThis" to declared),
        )
        assertEquals(declared, symbols.withSyntheticGlobalThis().variables.getValue("globalThis"))
    }

    @Test fun `self stands in when window is absent`() {
        val self = JsVariableInfo(type = "Window&any", location = JsLocation("lib.dom.d.ts", 42))
        val symbols = ParsedSymbols(variables = mapOf("self" to self))
        val globalThis = symbols.withSyntheticGlobalThis().variables.getValue("globalThis")
        assertEquals(self.type, globalThis.type)
        assertEquals(self.location, globalThis.location)
    }

    @Test fun `non-browser symbols are left untouched`() {
        val symbols = ParsedSymbols(variables = mapOf("process" to JsVariableInfo(type = "Process")))
        assertEquals(symbols, symbols.withSyntheticGlobalThis())
    }

    // ─── behaviour against the bundled index ────────────────────────────────────

    @Test fun `js-globalThis is a known global`() {
        assertTrue(index.isKnownGlobal("globalThis"))
        assertTrue("globalThis" in index.allGlobalNames())
    }

    @Test fun `js-globalThis resolves to Window`() {
        assertEquals("Window", JsTypeRef.parse(index.resolveGlobalType("globalThis")!!).primaryName())
    }

    @Test fun `js-globalThis is not treated as a constructor global`() {
        assertTrue(!index.isConstructorGlobal("globalThis"))
    }

    @Test fun `chains through globalThis resolve like chains through window`() {
        assertEquals(
            index.resolveJsChainType(listOf("window", "document")),
            index.resolveJsChainType(listOf("globalThis", "document")),
        )
        assertEquals("HTMLElement", index.resolveJsChainType(listOf("globalThis", "document", "body")))
        assertEquals("Location", index.resolveJsChainType(listOf("globalThis", "location")))
    }

    @Test fun `members resolve off the globalThis receiver type`() {
        val receiver = index.canonicalType(index.resolveGlobalType("globalThis")!!)
        assertNotNull(index.resolveMember(receiver, "addEventListener"))
        assertNotNull(index.resolveMember(receiver, "localStorage"))
    }

    @Test fun `globalThis navigates to the declaration that spells out its type`() {
        assertEquals(
            bundled.variables.getValue("window").location,
            index.resolveGlobalInfo("globalThis")?.location,
        )
    }
}
