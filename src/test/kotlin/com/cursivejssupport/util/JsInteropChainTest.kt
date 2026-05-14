package com.cursivejssupport.util

import org.junit.Assert.assertEquals
import org.junit.Test

class JsInteropChainTest {

    @Test
    fun pathAfterJsPrefixRawPreservesTrailingDot() {
        assertEquals("document.", JsInteropChain.pathAfterJsPrefixRaw("js/document.") ?: "")
        assertEquals("document", JsInteropChain.pathAfterJsPrefixRaw("js/document") ?: "")
    }

    @Test
    fun pathAfterJsPrefixTrimmedStripsTrailingDot() {
        assertEquals("document", JsInteropChain.pathAfterJsPrefixTrimmed("js/document.") ?: "")
        assertEquals("document.createRange", JsInteropChain.pathAfterJsPrefixTrimmed("js/document.createRange") ?: "")
    }

    @Test
    fun pathAfterJsPrefixAliasToTrimmed() {
        assertEquals("document", JsInteropChain.pathAfterJsPrefix("js/document.") ?: "")
    }

    @Test
    fun segmentsFromFullTextTrimsTrailingDot() {
        val seg = JsInteropChain.segmentsFromFullText("js/document.createRange.")!!
        assertEquals(listOf("document", "createRange"), seg)
    }
}
