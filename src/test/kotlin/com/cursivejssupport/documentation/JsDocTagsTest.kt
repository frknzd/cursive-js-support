package com.cursivejssupport.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsDocTagsTest {

    @Test fun `untagged legacy doc is all body`() {
        val d = JsDocTags.parse("Fetches things.\n\nSecond paragraph.")
        assertEquals("Fetches things.\n\nSecond paragraph.", d.body)
        assertTrue(d.params.isEmpty())
        assertNull(d.deprecated)
    }

    @Test fun `tagged doc splits into sections`() {
        val d = JsDocTags.parse(
            "Fetches things.\n\n" +
                "@param input the request input\n\n" +
                "@param init options\n\n" +
                "@returns a promise\n\n" +
                "@example doc.fetch(\"x\")\n\n" +
                "@throws TypeError on bad input\n\n" +
                "@see https://example.com\n\n" +
                "@deprecated use fetchAll instead",
        )
        assertEquals("Fetches things.", d.body)
        assertEquals(mapOf("input" to "the request input", "init" to "options"), d.params)
        assertEquals("a promise", d.returns)
        assertEquals(listOf("doc.fetch(\"x\")"), d.examples)
        assertEquals(listOf("TypeError on bad input"), d.throws)
        assertEquals(listOf("https://example.com"), d.sees)
        assertEquals("use fetchAll instead", d.deprecated)
    }

    @Test fun `bare deprecated tag yields empty reason`() {
        val d = JsDocTags.parse("Old thing.\n\n@deprecated")
        assertEquals("", d.deprecated)
    }

    @Test fun `unknown tags stay visible in the body`() {
        val d = JsDocTags.parse("@customTag something odd")
        assertEquals("@customTag something odd", d.body)
    }

    @Test fun `null and blank input`() {
        assertNull(JsDocTags.parse(null).body)
        assertNull(JsDocTags.parse("  ").body)
    }
}
