package com.cursivejssupport.completion

import com.intellij.codeInsight.completion.CompletionUtilCore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsInteropChainPrefixMatcherTest {

    @Test
    fun `partial member tail matches full js chain lookup`() {
        val m = JsInteropChainPrefixMatcher.fromEffectiveJs("js/document.cre")
        assertNotNull(m)
        assertTrue(m!!.prefixMatches("js/document.createRange"))
        assertFalse(m.prefixMatches("js/window.alert"))
    }

    @Test
    fun `global prefix without member segment`() {
        val m = JsInteropChainPrefixMatcher.fromEffectiveJs("js/doc")
        assertNotNull(m)
        assertTrue(m!!.prefixMatches("js/document"))
        assertFalse(m.prefixMatches("js/window"))
    }

    @Test
    fun `leading list paren before js is normalized for matcher`() {
        val m = JsInteropChainPrefixMatcher.fromEffectiveJs("(js/document.cre")
        assertNotNull(m)
        assertTrue(m!!.prefixMatches("js/document.createRange"))
    }

    @Test
    fun `strips dummy identifier noise`() {
        val m = JsInteropChainPrefixMatcher.fromEffectiveJs(
            "js/document.${CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED}",
        )
        assertNotNull(m)
        assertTrue(m!!.prefixMatches("js/document.createElement"))
    }
}
