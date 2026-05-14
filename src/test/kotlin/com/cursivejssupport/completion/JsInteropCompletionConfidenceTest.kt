package com.cursivejssupport.completion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsInteropCompletionConfidenceTest {

    @Test
    fun sliceWithBareJsPrefixIsRecognized() {
        assertTrue(JsInteropCompletionConfidence.sliceLooksJsInterop("js/"))
        assertTrue(JsInteropCompletionConfidence.sliceLooksJsInterop("js/document"))
        assertTrue(JsInteropCompletionConfidence.sliceLooksJsInterop("js/document."))
    }

    @Test
    fun sliceWithListPrefixIsRecognized() {
        assertTrue(JsInteropCompletionConfidence.sliceLooksJsInterop("(js/document.createRange"))
        assertTrue(JsInteropCompletionConfidence.sliceLooksJsInterop("foo bar (js/document"))
    }

    @Test
    fun sliceWithJsAfterWhitespaceIsRecognized() {
        assertTrue(JsInteropCompletionConfidence.sliceLooksJsInterop("call js/window"))
        assertTrue(JsInteropCompletionConfidence.sliceLooksJsInterop("a\njs/document.createRange"))
        assertTrue(JsInteropCompletionConfidence.sliceLooksJsInterop("a\tjs/window"))
    }

    @Test
    fun sliceWithoutJsTokenIsNotRecognized() {
        assertFalse(JsInteropCompletionConfidence.sliceLooksJsInterop(""))
        assertFalse(JsInteropCompletionConfidence.sliceLooksJsInterop("(foo bar)"))
        assertFalse(JsInteropCompletionConfidence.sliceLooksJsInterop("(.startContainer range"))
        assertFalse(JsInteropCompletionConfidence.sliceLooksJsInterop("(my.ns/foo bar)"))
    }

    @Test
    fun sliceWithEmbeddedJsButNoTokenBoundaryIsNotRecognized() {
        // `myjs/` is NOT a js interop token - we need a real word boundary.
        assertFalse(JsInteropCompletionConfidence.sliceLooksJsInterop("(myjs/foo"))
    }
}
