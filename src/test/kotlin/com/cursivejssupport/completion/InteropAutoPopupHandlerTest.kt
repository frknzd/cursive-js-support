package com.cursivejssupport.completion

import com.cursivejssupport.npm.NpmBinding
import com.cursivejssupport.npm.NpmBindingKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropAutoPopupHandlerTest {

    @Test
    fun `pops for js slash typed`() {
        val text = "(js/"
        assertTrue(InteropAutoPopupHandler.shouldOpen(text, text.length))
    }

    @Test
    fun `pops for js dotted chain`() {
        val text = "(js/document."
        assertTrue(InteropAutoPopupHandler.shouldOpen(text, text.length))
    }

    @Test
    fun `pops inside require string`() {
        val text = "(ns my.app (:require [\""
        assertTrue(InteropAutoPopupHandler.shouldOpen(text, text.length))
    }

    @Test
    fun `pops inside refer bracket`() {
        val text = "(ns my.app (:require [\"react\" :refer ["
        assertTrue(InteropAutoPopupHandler.shouldOpen(text, text.length))
    }

    @Test
    fun `pops for dot method`() {
        val text = "(."
        assertTrue(InteropAutoPopupHandler.shouldOpen(text, text.length))
    }

    @Test
    fun `pops for dot property`() {
        val text = "(.-"
        assertTrue(InteropAutoPopupHandler.shouldOpen(text, text.length))
    }

    @Test
    fun `pops for npm alias`() {
        val text = "(React/"
        assertTrue(InteropAutoPopupHandler.shouldOpen(text, text.length, mapOf("React" to NpmBinding("react", NpmBindingKind.AS))))
    }

    @Test
    fun `does not pop for bare identifier`() {
        val text = "(foo "
        assertFalse(InteropAutoPopupHandler.shouldOpen(text, text.length))
    }

    @Test
    fun `does not pop inside arbitrary string`() {
        val text = "(def x \"hello"
        assertFalse(InteropAutoPopupHandler.shouldOpen(text, text.length))
    }
}
