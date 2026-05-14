package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsVariableInfo
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.assertEquals
import org.junit.Test

class JsInteropJsCompletionSubBranchTest {

    private fun indexWithDocument(): JsSymbolIndex {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Document" to JsInterface(
                        members = mapOf(
                            "createRange" to listOf(JsMember(kind = "method", returns = "Range")),
                            "body" to listOf(JsMember(kind = "property", type = "HTMLElement")),
                        ),
                    ),
                    "Range" to JsInterface(members = emptyMap()),
                ),
                variables = mapOf(
                    "document" to JsVariableInfo(type = "Document"),
                ),
            ),
        )
        index.setLoaded(true)
        return index
    }

    @Test
    fun `document trailing dot is members after global not chain`() {
        val index = indexWithDocument()
        assertEquals(
            JsInteropJsCompletionSubBranch.MembersAfterGlobal,
            classifyJsInteropJsCompletionSubBranch("document.", "document", index),
        )
    }

    @Test
    fun `document createRange is chain`() {
        val index = indexWithDocument()
        assertEquals(
            JsInteropJsCompletionSubBranch.ChainMembers,
            classifyJsInteropJsCompletionSubBranch(
                "document.createRange",
                "document.createRange",
                index,
            ),
        )
    }

    @Test
    fun `doc prefix is globals filtered`() {
        val index = indexWithDocument()
        assertEquals(
            JsInteropJsCompletionSubBranch.GlobalsFiltered,
            classifyJsInteropJsCompletionSubBranch("doc", "doc", index),
        )
    }

    @Test
    fun `unknown global trailing dot falls through to chain then yields no emits elsewhere`() {
        val index = indexWithDocument()
        assertEquals(
            JsInteropJsCompletionSubBranch.ChainMembers,
            classifyJsInteropJsCompletionSubBranch("unknown.", "unknown", index),
        )
    }
}
