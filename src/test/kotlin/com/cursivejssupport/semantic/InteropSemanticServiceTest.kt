package com.cursivejssupport.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InteropSemanticServiceTest {
    @Test
    fun `partial live exports do not hide declaration index exports`() {
        val result = mergeSemanticExports(
            live = listOf(SemanticExport("diffChars", null)),
            indexedNames = listOf("diffChars", "diffArrays"),
        )

        assertEquals(listOf("diffChars", "diffArrays"), result.map(SemanticExport::name))
        assertNull(result.single { it.name == "diffArrays" }.declaration)
    }
}
