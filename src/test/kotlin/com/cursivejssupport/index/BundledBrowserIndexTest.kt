package com.cursivejssupport.index

import com.cursivejssupport.parser.ParsedSymbols
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Smoke tests against the regenerated bundled `browser-symbols.json.gz`: generic
 * instantiations, type parameters, union aliases, and tag-prefixed JSDoc must survive the
 * extractor → gzip → Jackson round trip.
 */
class BundledBrowserIndexTest {

    companion object {
        private lateinit var index: JsSymbolIndex

        @JvmStatic
        @BeforeClass
        fun load() {
            val file = File("src/main/resources/js/browser-symbols.json.gz")
            assumeTrue("bundled index missing", file.isFile)
            val symbols = GZIPInputStream(file.inputStream()).use {
                jacksonObjectMapper().readValue<ParsedSymbols>(it)
            }
            index = JsSymbolIndex()
            index.load(symbols)
            index.setLoaded(true)
        }
    }

    @Test fun `fetch returns a generic Promise`() {
        assertEquals("Promise<Response>", index.resolveFunctions("fetch")?.first()?.returns)
    }

    @Test fun `querySelectorAll chain flows through NodeListOf substitution`() {
        // document.querySelectorAll(sel): NodeListOf<E>; NodeListOf<TNode>.item(): TNode.
        val listType = index.resolveJsChainTypeRef(listOf("document", "querySelectorAll"))
        assertEquals("NodeListOf", listType?.primaryName())
        // Chain continues into NodeList members via primaryName.
        assertEquals("number", index.resolveJsChainType(listOf("document", "querySelectorAll", "length")))
    }

    @Test fun `array-typed members resolve through the Array interface`() {
        // Navigator.languages: ReadonlyArray<string>/string[] style member must not dead-end.
        val styles = index.resolveMember("Document", "styleSheets")
        // A generic collection type still resolves members somewhere down the chain:
        assertTrue(index.canonicalType("Element[]") == "Array")
    }

    @Test fun `union aliases expand to concrete interfaces`() {
        // BodyInit = ReadableStream|XMLHttpRequestBodyInit — canonicalType must land on an interface.
        val t = index.canonicalType("BodyInit")
        assertTrue("BodyInit should expand to a concrete interface, was $t", index.resolveInterface(t) != null)
    }

    @Test fun `deprecated members carry the deprecated tag`() {
        // document.charset is a classic @deprecated DOM member.
        val doc = index.resolveMember("Document", "charset")?.first?.doc.orEmpty()
        assertTrue("charset doc should carry @deprecated: $doc", doc.contains("@deprecated"))
    }

    /**
     * Core ECMAScript constructors are declared as `declare var Error: ErrorConstructor` — a named
     * companion interface, not the `TYPE$X` literal the DOM libs use. Recognizing only the latter
     * made `(js/Error. "boom")` report "'Error' is not constructable" against the real index while
     * the hand-built fixtures stayed green.
     */
    @Test fun `named companion constructors are constructable`() {
        listOf(
            "Error", "TypeError", "RangeError", "SyntaxError", "AggregateError",
            "Date", "Map", "Set", "WeakMap", "Promise", "Proxy", "Array", "Object",
            "RegExp", "ArrayBuffer", "DataView", "Uint8Array", "FinalizationRegistry",
        ).forEach { assertTrue("$it should be constructable", index.isConstructorGlobal(it)) }
    }

    @Test fun `TYPE-dollar companion constructors stay constructable`() {
        listOf("Blob", "URL", "Worker", "Event", "XMLHttpRequest", "TextEncoder", "AbortController")
            .forEach { assertTrue("$it should be constructable", index.isConstructorGlobal(it)) }
    }

    @Test fun `companions without a new signature are not constructable`() {
        // Flag bags and singletons: `(js/NodeFilter.)` / `(js/Math.)` are not constructor calls,
        // and `new Symbol()` / `new BigInt()` throw at runtime.
        listOf("NodeFilter", "Iterator", "Math", "JSON", "Reflect", "Symbol", "BigInt", "document", "window")
            .forEach { assertTrue("$it should not be constructable", !index.isConstructorGlobal(it)) }
    }

    @Test fun `construct signatures come from the named companion`() {
        val overloads = index.globalConstructSignatures("Error")
        assertTrue("Error should declare new overloads", overloads.isNotEmpty())
        assertTrue("Error's new should return Error", overloads.all { it.returns == "Error" })
        assertTrue(
            "Error's new should accept a message",
            overloads.any { it.params.firstOrNull()?.type == "string" },
        )
    }
}
