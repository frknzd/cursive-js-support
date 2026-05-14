package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsVariableInfo
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsInteropIndexQueriesTest {

    @Test
    fun dottedJsRestResolvableForDocumentCreateRange() {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Document" to JsInterface(
                        members = mapOf(
                            "createRange" to listOf(JsMember(kind = "method", returns = "Range"))
                        )
                    ),
                    "Range" to JsInterface(members = emptyMap())
                ),
                variables = mapOf(
                    "document" to JsVariableInfo(type = "Document")
                )
            )
        )
        index.setLoaded(true)
        assertTrue(JsInteropIndexQueries.isResolvableDottedJsRest(index, "document.createRange"))
        assertFalse(JsInteropIndexQueries.isResolvableDottedJsRest(index, "document.missing"))
    }

    @Test
    fun enclosingSymbolJsChainResolvable() {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Document" to JsInterface(
                        members = mapOf(
                            "createRange" to listOf(JsMember(kind = "method", returns = "Range"))
                        )
                    ),
                    "Range" to JsInterface(members = emptyMap())
                ),
                variables = mapOf(
                    "document" to JsVariableInfo(type = "Document")
                )
            )
        )
        index.setLoaded(true)
        assertTrue(
            JsInteropIndexQueries.isResolvableJsInteropSymbol(
                index,
                "js/document.createRange",
                null,
                null,
            )
        )
    }
}
