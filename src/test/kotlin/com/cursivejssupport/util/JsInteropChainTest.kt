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
    fun reconcileAppendsVirtualDotWhenEditorHasDot() {
        assertEquals("document.", JsInteropChain.reconcileJsPathRawWithTrailingEditorDot("document", true))
        assertEquals("document", JsInteropChain.reconcileJsPathRawWithTrailingEditorDot("document", false))
        assertEquals("document.", JsInteropChain.reconcileJsPathRawWithTrailingEditorDot("document.", true))
    }
}
