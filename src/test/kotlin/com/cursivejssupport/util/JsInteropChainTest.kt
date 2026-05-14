package com.cursivejssupport.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsInteropChainTest {

    @Test
    fun segmentsFromFullTextSplitsOnDots() {
        assertEquals(listOf("document", "createRange"), JsInteropChain.segmentsFromFullText("js/document.createRange"))
        assertEquals(listOf("document"), JsInteropChain.segmentsFromFullText("js/document"))
    }

    @Test
    fun segmentsFromFullTextTrimsTrailingDot() {
        assertEquals(listOf("document"), JsInteropChain.segmentsFromFullText("js/document."))
    }

    @Test
    fun segmentsFromFullTextReturnsEmptyListForBareJsPrefix() {
        assertEquals(emptyList<String>(), JsInteropChain.segmentsFromFullText("js/"))
    }

    @Test
    fun segmentsFromFullTextRejectsNonJsText() {
        assertNull(JsInteropChain.segmentsFromFullText("foo.bar"))
    }

    @Test
    fun segmentsFromSymbolFallsBackToNamespacePlusName() {
        assertEquals(
            listOf("document", "createRange"),
            JsInteropChain.segmentsFromSymbol("js", "document.createRange", "document.createRange"),
        )
    }
}
