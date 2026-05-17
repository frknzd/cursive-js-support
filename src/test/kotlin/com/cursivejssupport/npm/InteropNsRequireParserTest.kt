package com.cursivejssupport.npm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropNsRequireParserTest {

    private fun parse(text: String) = InteropNsRequireParser.parse(text, text.length)

    @Test
    fun `partial package name inside string literal`() {
        val slot = parse("(ns my.app (:require [\"rea")
        assertTrue(slot is InteropNsRequireParser.Slot.Package)
        slot as InteropNsRequireParser.Slot.Package
        assertEquals("rea", slot.prefix)
    }

    @Test
    fun `empty package string slot`() {
        val slot = parse("(ns my.app (:require [\"")
        assertTrue(slot is InteropNsRequireParser.Slot.Package)
        slot as InteropNsRequireParser.Slot.Package
        assertEquals("", slot.prefix)
    }

    @Test
    fun `string outside ns require is not a slot`() {
        assertNull(parse("(def x \"react"))
    }

    @Test
    fun `keyword slot right after closing package quote`() {
        val slot = parse("(ns my.app (:require [\"react\" ")
        assertTrue(slot is InteropNsRequireParser.Slot.Keyword)
        slot as InteropNsRequireParser.Slot.Keyword
        assertEquals("react", slot.packageName)
        assertEquals("", slot.prefix)
        assertTrue(":as" in slot.availableKeywords)
        assertTrue(":refer" in slot.availableKeywords)
        assertTrue(":rename" in slot.availableKeywords)
        assertTrue(":default" in slot.availableKeywords)
        assertTrue(":all" in slot.availableKeywords)
    }

    @Test
    fun `all alias position is not a completion slot`() {
        assertNull(parse("(ns my.app (:require [\"react\" :all MyAl"))
    }

    @Test
    fun `keyword slot omits already used all keyword`() {
        val slot = parse("(ns my.app (:require [\"react\" :all MyAlias ")
        assertTrue(slot is InteropNsRequireParser.Slot.Keyword)
        slot as InteropNsRequireParser.Slot.Keyword
        assertTrue(":all" !in slot.availableKeywords)
        assertTrue(":refer" in slot.availableKeywords)
    }

    @Test
    fun `keyword slot partial keyword typed`() {
        val slot = parse("(ns my.app (:require [\"react\" :r")
        assertTrue(slot is InteropNsRequireParser.Slot.Keyword)
        slot as InteropNsRequireParser.Slot.Keyword
        assertEquals("react", slot.packageName)
        assertEquals(":r", slot.prefix)
    }

    @Test
    fun `as alias position is not a completion slot`() {
        // `["react" :as React|` — the user is naming the alias, not picking from a fixed set.
        assertNull(parse("(ns my.app (:require [\"react\" :as Reac"))
    }

    @Test
    fun `keyword slot omits already used keywords`() {
        val slot = parse("(ns my.app (:require [\"react\" :as React ")
        assertTrue(slot is InteropNsRequireParser.Slot.Keyword)
        slot as InteropNsRequireParser.Slot.Keyword
        assertTrue(":as" !in slot.availableKeywords)
        assertTrue(":refer" in slot.availableKeywords)
        assertTrue(":rename" in slot.availableKeywords)
    }

    @Test
    fun `refer collection partial symbol`() {
        val slot = parse("(ns my.app (:require [\"react\" :refer [u")
        assertTrue(slot is InteropNsRequireParser.Slot.Refer)
        slot as InteropNsRequireParser.Slot.Refer
        assertEquals("react", slot.packageName)
        assertEquals("u", slot.prefix)
    }

    @Test
    fun `refer collection empty bracket`() {
        val slot = parse("(ns my.app (:require [\"react\" :refer [")
        assertTrue(slot is InteropNsRequireParser.Slot.Refer)
        slot as InteropNsRequireParser.Slot.Refer
        assertEquals("react", slot.packageName)
        assertEquals("", slot.prefix)
    }

    @Test
    fun `rename map partial`() {
        val slot = parse("(ns my.app (:require [\"react\" :rename {use")
        assertTrue(slot is InteropNsRequireParser.Slot.Refer)
        slot as InteropNsRequireParser.Slot.Refer
        assertEquals("react", slot.packageName)
        assertEquals("use", slot.prefix)
    }

    @Test
    fun `multiline ns block`() {
        val slot = parse(
            """
            (ns my.app
              (:require
                ["react" :refer [useS""".trimIndent()
        )
        assertTrue(slot is InteropNsRequireParser.Slot.Refer)
        slot as InteropNsRequireParser.Slot.Refer
        assertEquals("react", slot.packageName)
        assertEquals("useS", slot.prefix)
    }

    @Test
    fun `second spec partial package`() {
        val slot = parse(
            """
            (ns my.app
              (:require
                ["react" :as React]
                ["react-d""".trimIndent()
        )
        assertTrue(slot is InteropNsRequireParser.Slot.Package)
        slot as InteropNsRequireParser.Slot.Package
        assertEquals("react-d", slot.prefix)
    }

    @Test
    fun `outside any require returns null`() {
        assertNull(parse("(defn foo [x] (.log js/console x))"))
    }
}
