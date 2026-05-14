package com.cursivejssupport.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropContextDetectorTest {

    private fun detect(text: String, aliases: Map<String, String> = emptyMap()): InteropCompletionContext =
        InteropContextDetector.detect(text, text.length, aliases)

    @Test
    fun `js slash empty prefix yields js global with empty prefix`() {
        val ctx = detect("(js/")
        assertTrue(ctx is InteropCompletionContext.JsGlobalName)
        ctx as InteropCompletionContext.JsGlobalName
        assertEquals("", ctx.prefix)
        assertEquals("(js/".length, ctx.replacementStart)
    }

    @Test
    fun `js slash partial global`() {
        val ctx = detect("(js/doc")
        assertTrue(ctx is InteropCompletionContext.JsGlobalName)
        ctx as InteropCompletionContext.JsGlobalName
        assertEquals("doc", ctx.prefix)
        assertEquals("(js/".length, ctx.replacementStart)
    }

    @Test
    fun `js chain with trailing dot yields chain member empty prefix`() {
        val ctx = detect("(js/document.")
        assertTrue(ctx is InteropCompletionContext.JsChainMember)
        ctx as InteropCompletionContext.JsChainMember
        assertEquals(listOf("document"), ctx.receiverSegments)
        assertEquals("", ctx.prefix)
        assertEquals("(js/document.".length, ctx.replacementStart)
    }

    @Test
    fun `js chain with partial member`() {
        val ctx = detect("(js/document.cre")
        assertTrue(ctx is InteropCompletionContext.JsChainMember)
        ctx as InteropCompletionContext.JsChainMember
        assertEquals(listOf("document"), ctx.receiverSegments)
        assertEquals("cre", ctx.prefix)
        assertEquals("(js/document.".length, ctx.replacementStart)
    }

    @Test
    fun `js multi segment chain`() {
        val ctx = detect("(js/console.log.app")
        assertTrue(ctx is InteropCompletionContext.JsChainMember)
        ctx as InteropCompletionContext.JsChainMember
        assertEquals(listOf("console", "log"), ctx.receiverSegments)
        assertEquals("app", ctx.prefix)
    }

    @Test
    fun `dot method token at head`() {
        val ctx = detect("(.lo")
        assertTrue(ctx is InteropCompletionContext.DotMember)
        ctx as InteropCompletionContext.DotMember
        assertEquals(false, ctx.asProperty)
        assertEquals("lo", ctx.prefix)
        assertEquals("(.".length, ctx.replacementStart)
    }

    @Test
    fun `dot property token at head`() {
        val ctx = detect("(.-cla")
        assertTrue(ctx is InteropCompletionContext.DotMember)
        ctx as InteropCompletionContext.DotMember
        assertEquals(true, ctx.asProperty)
        assertEquals("cla", ctx.prefix)
        assertEquals("(.-".length, ctx.replacementStart)
    }

    @Test
    fun `bare dot yields dot method with empty prefix`() {
        val ctx = detect("(.")
        assertTrue(ctx is InteropCompletionContext.DotMember)
        ctx as InteropCompletionContext.DotMember
        assertEquals(false, ctx.asProperty)
        assertEquals("", ctx.prefix)
    }

    @Test
    fun `bare dot dash yields dot property with empty prefix`() {
        val ctx = detect("(.-")
        assertTrue(ctx is InteropCompletionContext.DotMember)
        ctx as InteropCompletionContext.DotMember
        assertEquals(true, ctx.asProperty)
        assertEquals("", ctx.prefix)
    }

    @Test
    fun `npm alias export prefix`() {
        val ctx = detect("(Markdown/defa", aliases = mapOf("Markdown" to "react-markdown"))
        assertTrue(ctx is InteropCompletionContext.NpmAliasExport)
        ctx as InteropCompletionContext.NpmAliasExport
        assertEquals("Markdown", ctx.alias)
        assertEquals("react-markdown", ctx.packageName)
        assertEquals("defa", ctx.prefix)
        assertEquals("(Markdown/".length, ctx.replacementStart)
    }

    @Test
    fun `npm alias export member chain`() {
        val ctx = detect("(Markdown/default.ren", aliases = mapOf("Markdown" to "react-markdown"))
        assertTrue(ctx is InteropCompletionContext.NpmAliasExportMember)
        ctx as InteropCompletionContext.NpmAliasExportMember
        assertEquals("Markdown", ctx.alias)
        assertEquals("react-markdown", ctx.packageName)
        assertEquals("default", ctx.exportName)
        assertEquals(emptyList<String>(), ctx.receiverSegments)
        assertEquals("ren", ctx.prefix)
    }

    @Test
    fun `npm alias export member chain deeper receiver`() {
        val ctx = detect("(Markdown/default.props.cla", aliases = mapOf("Markdown" to "react-markdown"))
        assertTrue(ctx is InteropCompletionContext.NpmAliasExportMember)
        ctx as InteropCompletionContext.NpmAliasExportMember
        assertEquals("default", ctx.exportName)
        assertEquals(listOf("props"), ctx.receiverSegments)
        assertEquals("cla", ctx.prefix)
    }

    @Test
    fun `unknown namespace is not an alias`() {
        val ctx = detect("(my.ns/foo")
        assertEquals(InteropCompletionContext.None, ctx)
    }

    @Test
    fun `bare identifier is none`() {
        val ctx = detect("(foo bar")
        assertEquals(InteropCompletionContext.None, ctx)
    }

    @Test
    fun `require package partial string`() {
        val ctx = detect("(ns my.app (:require [\"rea")
        assertTrue(ctx is InteropCompletionContext.NsRequirePackage)
        ctx as InteropCompletionContext.NsRequirePackage
        assertEquals("rea", ctx.prefix)
    }

    @Test
    fun `require empty package string`() {
        val ctx = detect("(ns my.app (:require [\"")
        assertTrue(ctx is InteropCompletionContext.NsRequirePackage)
        ctx as InteropCompletionContext.NsRequirePackage
        assertEquals("", ctx.prefix)
    }

    @Test
    fun `string outside require is not an interop context`() {
        val ctx = detect("(def x \"hello")
        assertEquals(InteropCompletionContext.None, ctx)
    }

    @Test
    fun `refer collection partial symbol`() {
        val ctx = detect("(ns my.app (:require [\"react\" :refer [u")
        assertTrue(ctx is InteropCompletionContext.NsRefer)
        ctx as InteropCompletionContext.NsRefer
        assertEquals("react", ctx.packageName)
        assertEquals("u", ctx.prefix)
    }

    @Test
    fun `refer collection empty bracket`() {
        val ctx = detect("(ns my.app (:require [\"react\" :refer [")
        assertTrue(ctx is InteropCompletionContext.NsRefer)
        ctx as InteropCompletionContext.NsRefer
        assertEquals("react", ctx.packageName)
        assertEquals("", ctx.prefix)
    }

    @Test
    fun `rename map partial key`() {
        val ctx = detect("(ns my.app (:require [\"react\" :rename {use")
        assertTrue(ctx is InteropCompletionContext.NsRefer)
        ctx as InteropCompletionContext.NsRefer
        assertEquals("react", ctx.packageName)
        assertEquals("use", ctx.prefix)
    }

    @Test
    fun `require spec keyword slot after package`() {
        val ctx = detect("(ns my.app (:require [\"react\" ")
        assertTrue(ctx is InteropCompletionContext.NsRequireKeyword)
        ctx as InteropCompletionContext.NsRequireKeyword
        assertEquals("react", ctx.packageName)
        assertTrue(":as" in ctx.availableKeywords)
        assertTrue(":refer" in ctx.availableKeywords)
        assertTrue(":rename" in ctx.availableKeywords)
        assertTrue(":default" in ctx.availableKeywords)
    }

    @Test
    fun `require spec keyword excludes already used keywords`() {
        val ctx = detect("(ns my.app (:require [\"react\" :as React ")
        assertTrue(ctx is InteropCompletionContext.NsRequireKeyword)
        ctx as InteropCompletionContext.NsRequireKeyword
        assertTrue(":as" !in ctx.availableKeywords)
        assertTrue(":refer" in ctx.availableKeywords)
    }

    @Test
    fun `bare js token without slash is none`() {
        val ctx = detect("(js")
        assertEquals(InteropCompletionContext.None, ctx)
    }

    @Test
    fun `chain after caret matches independent of trailing whitespace`() {
        // user types '(js/document.' then space → token ends at the dot, caret is past space
        val text = "(js/document. "
        val ctx = InteropContextDetector.detect(text, text.length, emptyMap())
        // whitespace ends the token; nothing on the right side after the dot — this is bare text
        assertEquals(InteropCompletionContext.None, ctx)
    }
}
