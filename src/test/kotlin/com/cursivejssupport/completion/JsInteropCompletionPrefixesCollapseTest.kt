package com.cursivejssupport.completion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsInteropCompletionPrefixesCollapseTest {

    @Test
    fun collapseStripsDummySlashTail() {
        assertEquals(
            "js/document",
            JsInteropCompletionPrefixes.collapseInvalidSlashesInJsInteropNormalizedPrefix("js/document/cursive-completion"),
        )
    }

    @Test
    fun collapseLeavesDots() {
        assertEquals(
            "js/document.createRange",
            JsInteropCompletionPrefixes.collapseInvalidSlashesInJsInteropNormalizedPrefix("js/document.createRange"),
        )
    }

    @Test
    fun suppressOnlyWhenSlashAfterGlobal() {
        assertTrue(
            JsInteropCompletionPrefixes.shouldSuppressAutoPopupAfterInvalidJsSlash("(js/document/"),
        )
        assertFalse(
            JsInteropCompletionPrefixes.shouldSuppressAutoPopupAfterInvalidJsSlash("(js/"),
        )
    }
}
