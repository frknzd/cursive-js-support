package com.cursivejssupport.npm

import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowNpmDepsParserTest {

    @Test
    fun extractsNpmDepsFromNestedEdn() {
        val edn = """
            {:builds {:app {:target :browser}}
             :npm-deps {"react" "18.2.0" "lodash" "4.x"}}
        """.trimIndent()
        val names = ShadowNpmDepsParser.collectFromEdnText(edn)
        assertTrue(names.contains("react"))
        assertTrue(names.contains("lodash"))
    }
}
