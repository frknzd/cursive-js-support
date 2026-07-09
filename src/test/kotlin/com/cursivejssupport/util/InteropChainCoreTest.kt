package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InteropChainCoreTest {

    // ─── parseStepHead / parseStepToken ─────────────────────────────────────

    @Test fun `dot-dot bare method step`() {
        assertEquals(
            ChainStepSpec("createRange", isProperty = false),
            InteropChainCore.parseStepHead("createRange", ChainKind.DOT_DOT),
        )
    }

    @Test fun `dot-dot bare property step`() {
        assertEquals(
            ChainStepSpec("body", isProperty = true),
            InteropChainCore.parseStepHead("-body", ChainKind.DOT_DOT),
        )
    }

    @Test fun `dot-dot tolerates dotted step heads`() {
        assertEquals(
            ChainStepSpec("foo", isProperty = false),
            InteropChainCore.parseStepHead(".foo", ChainKind.DOT_DOT),
        )
        assertEquals(
            ChainStepSpec("bar", isProperty = true),
            InteropChainCore.parseStepHead(".-bar", ChainKind.DOT_DOT),
        )
    }

    @Test fun `thread-first requires leading dot`() {
        assertEquals(
            ChainStepSpec("getMonth", isProperty = false),
            InteropChainCore.parseStepHead(".getMonth", ChainKind.THREAD_FIRST),
        )
        assertEquals(
            ChainStepSpec("length", isProperty = true),
            InteropChainCore.parseStepHead(".-length", ChainKind.THREAD_FIRST),
        )
        assertNull(InteropChainCore.parseStepHead("str", ChainKind.THREAD_FIRST))
        assertNull(InteropChainCore.parseStepHead("inc", ChainKind.DOTO))
    }

    @Test fun `double-dot head is never a step`() {
        assertNull(InteropChainCore.parseStepHead("..", ChainKind.THREAD_FIRST))
        assertNull(InteropChainCore.parseStepHead("..", ChainKind.DOT_DOT))
    }

    @Test fun `member names with delimiters or slashes are rejected`() {
        assertNull(InteropChainCore.parseStepHead("js/foo", ChainKind.DOT_DOT))
        assertNull(InteropChainCore.parseStepHead(".js/foo", ChainKind.THREAD_FIRST))
        assertNull(InteropChainCore.parseStepHead("(", ChainKind.DOT_DOT))
    }

    @Test fun `list-step token unwraps its head`() {
        assertEquals(
            ChainStepSpec("setAttribute", isProperty = false, isListStep = true),
            InteropChainCore.parseStepToken("(.setAttribute \"x\" \"1\")", ChainKind.THREAD_FIRST),
        )
        assertEquals(
            ChainStepSpec("createElement", isProperty = false, isListStep = true),
            InteropChainCore.parseStepToken("(createElement \"div\")", ChainKind.DOT_DOT),
        )
        assertNull(InteropChainCore.parseStepToken("(str \"x\")", ChainKind.THREAD_FIRST))
    }

    @Test fun `bare token parses as non-list step`() {
        assertEquals(
            ChainStepSpec("body", isProperty = true, isListStep = false),
            InteropChainCore.parseStepToken(".-body", ChainKind.SOME_FIRST),
        )
    }

    // ─── advance ─────────────────────────────────────────────────────────────

    private fun testIndex(): JsSymbolIndex {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Document" to JsInterface(
                        members = mapOf(
                            "createRange" to listOf(JsMember(kind = "method", returns = "Range")),
                            "body" to listOf(JsMember(kind = "property", type = "HTMLElement|null")),
                            "title" to listOf(JsMember(kind = "property", type = "string")),
                        ),
                    ),
                    "Range" to JsInterface(
                        members = mapOf(
                            "collapse" to listOf(JsMember(kind = "method", returns = "void")),
                        ),
                    ),
                    "HTMLElement" to JsInterface(
                        members = mapOf(
                            "focus" to listOf(JsMember(kind = "method", returns = "void")),
                        ),
                    ),
                ),
            ),
        )
        index.setLoaded(true)
        return index
    }

    @Test fun `advance follows method return type`() {
        assertEquals("Range", InteropChainCore.advance("Document", "createRange", testIndex()))
    }

    @Test fun `advance canonicalizes union property types`() {
        assertEquals("HTMLElement", InteropChainCore.advance("Document", "body", testIndex()))
    }

    @Test fun `advance bails on unknown members`() {
        assertNull(InteropChainCore.advance("Document", "nope", testIndex()))
    }

    @Test fun `advance bails on void results`() {
        assertNull(InteropChainCore.advance("Range", "collapse", testIndex()))
    }

    // ─── ChainKind semantics table ───────────────────────────────────────────

    @Test fun `kind flags match the semantics table`() {
        assertEquals(ChainKind.DOT_DOT, ChainKind.fromHead(".."))
        assertEquals(ChainKind.THREAD_FIRST, ChainKind.fromHead("->"))
        assertEquals(ChainKind.THREAD_LAST, ChainKind.fromHead("->>"))
        assertEquals(ChainKind.DOTO, ChainKind.fromHead("doto"))
        assertEquals(ChainKind.SOME_FIRST, ChainKind.fromHead("some->"))
        assertEquals(ChainKind.SOME_LAST, ChainKind.fromHead("some->>"))
        assertEquals(ChainKind.COND_FIRST, ChainKind.fromHead("cond->"))
        assertEquals(ChainKind.COND_LAST, ChainKind.fromHead("cond->>"))
        assertNull(ChainKind.fromHead("do"))
        assertNull(ChainKind.fromHead("->x"))
        assertNull(ChainKind.fromHead(null))

        assertEquals(true, ChainKind.DOT_DOT.bareSteps)
        assertEquals(false, ChainKind.THREAD_FIRST.bareSteps)
        assertEquals(true, ChainKind.THREAD_LAST.threadsLast)
        assertEquals(true, ChainKind.SOME_LAST.threadsLast)
        assertEquals(false, ChainKind.THREAD_FIRST.threadsLast)
        assertEquals(true, ChainKind.DOTO.rootReceiver)
        assertEquals(true, ChainKind.COND_FIRST.rootReceiver)
        assertEquals(false, ChainKind.THREAD_FIRST.rootReceiver)
        assertEquals(false, ChainKind.COND_FIRST.typeFlows)
        assertEquals(false, ChainKind.COND_LAST.typeFlows)
        assertEquals(true, ChainKind.DOTO.typeFlows)
    }
}
