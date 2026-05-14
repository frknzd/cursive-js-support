package com.cursivejssupport.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropCompletionIntentParserTest {

    @Test
    fun typedDotAfterJsGlobalPreservesTerminalDotDespiteCollapsedLogicalText() {
        val text = "(js/document."
        val intent = InteropCompletionIntentParser.parse(
            documentText = text,
            caretOffset = text.length,
            rawLogicalText = "document/cursive-completion",
            typedChar = '.',
        )

        assertEquals(InteropCompletionKind.Js, intent.kind)
        assertEquals(listOf("document"), intent.receiverSegments)
        assertTrue(intent.hadTerminalDot)
        assertFalse(intent.hadInvalidSlash)
        assertEquals("document.", intent.jsPathRaw)
    }

    @Test
    fun invalidSlashAfterJsGlobalIsNoneIntent() {
        val text = "(js/document/"
        val intent = InteropCompletionIntentParser.parse(
            documentText = text,
            caretOffset = text.length,
            rawLogicalText = "document/cursive-completion",
            typedChar = '/',
        )

        assertEquals(InteropCompletionKind.None, intent.kind)
        assertTrue(intent.hadInvalidSlash)
        assertTrue(InteropCompletionIntentParser.shouldSuppressInvalidSlash(text, text.length, '/'))
    }

    @Test
    fun partialJsMemberSplitsReceiverAndMemberPrefix() {
        val text = "(js/document.cre"
        val intent = InteropCompletionIntentParser.parse(
            documentText = text,
            caretOffset = text.length,
            rawLogicalText = "js/document.cre",
            typedChar = null,
        )

        assertEquals(InteropCompletionKind.Js, intent.kind)
        assertEquals(listOf("document"), intent.receiverSegments)
        assertEquals("cre", intent.memberPrefix)
        assertFalse(intent.hadTerminalDot)
    }

    @Test
    fun npmAliasMemberIntentTracksExportAndMemberPrefix() {
        val intent = InteropCompletionIntentParser.parse(
            documentText = "(Markdown/default.ren",
            caretOffset = "(Markdown/default.ren".length,
            rawLogicalText = "Markdown/default.ren",
            typedChar = null,
            aliases = mapOf("Markdown" to "react-markdown"),
        )

        assertEquals(InteropCompletionKind.NpmAlias, intent.kind)
        assertEquals("Markdown", intent.namespace)
        assertEquals("default", intent.exportName)
        assertEquals("ren", intent.memberPrefix)
    }
}
