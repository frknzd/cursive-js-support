package com.cursivejssupport.index

import com.cursivejssupport.index.JsTypeRef.Named
import com.cursivejssupport.index.JsTypeRef.Union
import org.junit.Assert.assertEquals
import org.junit.Test

class JsTypeRefTest {

    // ─── parse ───────────────────────────────────────────────────────────────

    @Test fun `plain name`() {
        assertEquals(Named("Document"), JsTypeRef.parse("Document"))
    }

    @Test fun `generic instantiation`() {
        assertEquals(Named("Promise", listOf(Named("Response"))), JsTypeRef.parse("Promise<Response>"))
    }

    @Test fun `nested generics with two args`() {
        assertEquals(
            Named("Map", listOf(Named("string"), Named("Promise", listOf(Named("Response"))))),
            JsTypeRef.parse("Map<string,Promise<Response>>"),
        )
    }

    @Test fun `array maps to Array of element`() {
        assertEquals(Named("Array", listOf(Named("Element"))), JsTypeRef.parse("Element[]"))
    }

    @Test fun `generic array`() {
        assertEquals(
            Named("Array", listOf(Named("HTMLCollectionOf", listOf(Named("Element"))))),
            JsTypeRef.parse("HTMLCollectionOf<Element>[]"),
        )
    }

    @Test fun `tuple retains positional component types`() {
        assertEquals(
            Named("Tuple", listOf(Named("string"), Named("ChangeObject"))),
            JsTypeRef.parse("[string, ChangeObject]"),
        )
        assertEquals("[string, ChangeObject]", JsTypeRef.parse("[string, ChangeObject]").display())
    }

    @Test fun `structural records retain nested optional and index types`() {
        val record = JsTypeRef.parse("{ added: boolean; count?: number; nested: { value: string } }")

        assertEquals(
            JsTypeRef.Record(
                linkedMapOf(
                    "added" to Named("boolean"),
                    "count" to Named("number"),
                    "nested" to JsTypeRef.Record(mapOf("value" to Named("string"))),
                ),
                optional = setOf("count"),
            ),
            record,
        )
        assertEquals("{ added: boolean; count?: number; nested: { value: string } }", record.display())
    }

    @Test fun union() {
        assertEquals(Union(listOf(Named("Node"), Named("null"))), JsTypeRef.parse("Node|null"))
    }

    @Test fun intersection() {
        assertEquals(
            Union(listOf(Named("Window"), Named("typeof globalThis")), intersection = true),
            JsTypeRef.parse("Window&typeof globalThis"),
        )
    }

    @Test fun `parenthesized union array`() {
        assertEquals(
            Named("Array", listOf(Union(listOf(Named("A"), Named("B"))))),
            JsTypeRef.parse("(A|B)[]"),
        )
    }

    @Test fun `whitespace tolerated`() {
        assertEquals(
            Named("Promise", listOf(Union(listOf(Named("Node"), Named("null"))))),
            JsTypeRef.parse("Promise< Node | null >"),
        )
    }

    @Test fun `garbage yields Unknown`() {
        assertEquals(JsTypeRef.Unknown, JsTypeRef.parse("Promise<"))
        assertEquals(JsTypeRef.Unknown, JsTypeRef.parse("<>"))
        assertEquals(JsTypeRef.Unknown, JsTypeRef.parse("A|"))
        assertEquals(JsTypeRef.Unknown, JsTypeRef.parse(""))
        assertEquals(JsTypeRef.Unknown, JsTypeRef.parse("()"))
        assertEquals(JsTypeRef.Unknown, JsTypeRef.parse("A]["))
    }

    // ─── display ─────────────────────────────────────────────────────────────

    @Test fun `display round trips`() {
        assertEquals("Promise<Response>", JsTypeRef.parse("Promise<Response>").display())
        assertEquals("Element[]", JsTypeRef.parse("Element[]").display())
        assertEquals("(A | B)[]", JsTypeRef.parse("(A|B)[]").display())
        assertEquals("Node | null", JsTypeRef.parse("Node|null").display())
        assertEquals("Window & any", JsTypeRef.parse("Window&any").display())
        assertEquals("Map<string, number>", JsTypeRef.parse("Map<string,number>").display())
        assertEquals("any", JsTypeRef.Unknown.display())
    }

    // ─── primaryName ─────────────────────────────────────────────────────────

    @Test fun `primaryName prefers concrete interface over primitives`() {
        assertEquals("CSSPositionValue", JsTypeRef.parse("string|CSSPositionValue").primaryName())
        assertEquals("Window", JsTypeRef.parse("Window&any").primaryName())
        assertEquals("Node", JsTypeRef.parse("Node|null").primaryName())
    }

    @Test fun `primaryName falls back to primitives over trivial`() {
        assertEquals("string", JsTypeRef.parse("string|null").primaryName())
        assertEquals("string", JsTypeRef.parse("string|number").primaryName())
    }

    @Test fun `primaryName strips generic args and maps arrays`() {
        assertEquals("Promise", JsTypeRef.parse("Promise<Response>").primaryName())
        assertEquals("Array", JsTypeRef.parse("Element[]").primaryName())
        assertEquals("NodeListOf", JsTypeRef.parse("NodeListOf<HTMLDivElement>").primaryName())
    }

    @Test fun `primaryName of trivial-only unions keeps first leaf`() {
        assertEquals("null", JsTypeRef.parse("null|undefined").primaryName())
        assertEquals("any", JsTypeRef.parse("any").primaryName())
    }

    @Test fun `primaryNamed carries generic args for substitution`() {
        val named = JsTypeRef.parse("NodeListOf<HTMLDivElement>|null").primaryNamed()
        assertEquals(Named("NodeListOf", listOf(Named("HTMLDivElement"))), named)
    }
}
