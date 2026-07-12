package com.cursivejssupport.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsInteropInspectionUtilTest {
    @Test fun `edit distance and nearest replacement are deterministic`() {
        assertEquals(1, editDistance("docment", "document"))
        assertEquals("document", nearest("docment", listOf("Document", "document", "location")))
        assertNull(nearest("xyz", listOf("document", "window")))
    }
}
