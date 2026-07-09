package com.cursivejssupport.index

import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsLocation
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [JsSymbolIndex.memberDeclarations] backs the ambiguous-member hover: unlike
 * [JsSymbolIndex.collectMemberCandidates] it must include members without a PSI location and
 * must NOT surface interfaces that only inherit the member.
 */
class JsSymbolIndexMemberDeclarationsTest {

    private fun buildIndex(): JsSymbolIndex {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    // Direct declarer WITH a location.
                    "Storage" to JsInterface(
                        members = mapOf(
                            "clear" to listOf(
                                JsMember(
                                    kind = "method", returns = "void",
                                    location = JsLocation(filePath = "/fake/lib.dom.d.ts", offset = 100),
                                )
                            )
                        )
                    ),
                    // Direct declarer WITHOUT a location — must still be reported.
                    "DOMTokenList" to JsInterface(
                        members = mapOf(
                            "clear" to listOf(JsMember(kind = "method", returns = "void"))
                        )
                    ),
                    // Inherits clear from Storage but declares nothing — must NOT be reported.
                    "SessionStorage" to JsInterface(extends = listOf("Storage")),
                ),
            )
        )
        return index
    }

    @Test
    fun `returns every direct declarer including location-less members`() {
        val declarations = buildIndex().memberDeclarations("clear")
        assertEquals(
            setOf("Storage", "DOMTokenList"),
            declarations.map { it.declaringType }.toSet(),
        )
        assertTrue(declarations.all { it.memberName == "clear" && it.overloads.isNotEmpty() })
    }

    @Test
    fun `excludes interfaces that only inherit the member`() {
        val declarations = buildIndex().memberDeclarations("clear")
        assertTrue(declarations.none { it.declaringType == "SessionStorage" })
    }

    @Test
    fun `unknown member name returns empty`() {
        assertTrue(buildIndex().memberDeclarations("definitelyNotAMember").isEmpty())
    }
}
