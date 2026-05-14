package com.cursivejssupport.npm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NsRequireCompletionUtilTest {

    @Test
    fun `caret immediately after opening quote yields empty prefix`() {
        val text = "(ns my.app (:require [\""
        val caret = text.length
        assertEquals("", NsRequireCompletionUtil.npmPackagePrefixInRequireText(text, caret))
    }

    @Test
    fun `partial package name inside string`() {
        val text = "(ns my.app (:require [\"rea"
        val caret = text.length
        assertEquals("rea", NsRequireCompletionUtil.npmPackagePrefixInRequireText(text, caret))
    }

    @Test
    fun `outside require returns null`() {
        val text = "(def x \"rea"
        val caret = text.length
        assertNull(NsRequireCompletionUtil.npmPackagePrefixInRequireText(text, caret))
    }
}
