package com.cursivejssupport.index

import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsLocation
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the dedup logic in [JsSymbolIndex.getAnyMemberPsiElements] via the package-private
 * helpers — driving the full method requires a Project, which is overkill for verifying the
 * (file, offset) grouping.
 */
class JsSymbolIndexDedupTest {

    @Test
    fun `same location across inheritance dedupes to smallest distance`() {
        val loc = JsLocation(filePath = "/fake/lib.dom.d.ts", offset = 1000)
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    // Element declares scrollTop directly at /fake/lib.dom.d.ts:1000.
                    "Element" to JsInterface(
                        members = mapOf(
                            "scrollTop" to listOf(
                                JsMember(kind = "property", type = "number", location = loc)
                            )
                        )
                    ),
                    // HTMLElement & SVGElement both extend Element, so resolveMember will surface
                    // the same scrollTop through inheritance at the same `(file, offset)`.
                    "HTMLElement" to JsInterface(extends = listOf("Element")),
                    "SVGElement" to JsInterface(extends = listOf("Element")),
                ),
            )
        )

        val candidates = index.collectMemberCandidates("scrollTop")
        // We expect one direct hit (Element, distance=0) plus two inherited hits
        // (HTMLElement / SVGElement, both pointing at the same location with declaring
        // interface "Element").
        assertEquals(3, candidates.size)

        val deduped = index.dedupeMemberCandidatesByLocation(candidates)
        assertEquals(1, deduped.size)
        val survivor = deduped.first()
        assertEquals("Element", survivor.declaringInterface)
        assertEquals(0, survivor.distance)
    }

    @Test
    fun `different locations are preserved`() {
        val a = JsLocation(filePath = "/fake/lib.dom.d.ts", offset = 100)
        val b = JsLocation(filePath = "/fake/lib.dom.d.ts", offset = 200)
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Foo" to JsInterface(
                        members = mapOf(
                            "thing" to listOf(JsMember(kind = "method", returns = "void", location = a))
                        )
                    ),
                    "Bar" to JsInterface(
                        members = mapOf(
                            "thing" to listOf(JsMember(kind = "method", returns = "void", location = b))
                        )
                    ),
                )
            )
        )
        val deduped = index.dedupeMemberCandidatesByLocation(index.collectMemberCandidates("thing"))
        assertEquals(2, deduped.size)
        assertTrue(deduped.any { it.location.offset == 100 })
        assertTrue(deduped.any { it.location.offset == 200 })
    }

    @Test
    fun `preferred receiver ranks first in sort`() {
        val locA = JsLocation(filePath = "/fake/lib.dom.d.ts", offset = 100)
        val locB = JsLocation(filePath = "/fake/lib.dom.d.ts", offset = 200)
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Document" to JsInterface(
                        members = mapOf(
                            "createX" to listOf(JsMember(kind = "method", returns = "X", location = locA))
                        )
                    ),
                    "OtherDoc" to JsInterface(
                        members = mapOf(
                            "createX" to listOf(JsMember(kind = "method", returns = "X", location = locB))
                        )
                    ),
                )
            )
        )
        val candidates = index.dedupeMemberCandidatesByLocation(index.collectMemberCandidates("createX"))
        val sortedPref = index.sortMemberCandidates(candidates, preferredReceiverType = "Document")
        assertEquals("Document", sortedPref.first().declaringInterface)

        val sortedAlt = index.sortMemberCandidates(candidates, preferredReceiverType = "OtherDoc")
        assertEquals("OtherDoc", sortedAlt.first().declaringInterface)
    }
}
