package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsVariableInfo
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.assertEquals
import org.junit.Test

class InteropCompletionRouterTest {

    private fun index(): JsSymbolIndex {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Document" to JsInterface(
                        members = mapOf("createRange" to listOf(JsMember(kind = "method", returns = "Range"))),
                    ),
                    "Range" to JsInterface(members = emptyMap()),
                    "MarkdownComponent" to JsInterface(
                        members = mapOf("render" to listOf(JsMember(kind = "method", returns = "void"))),
                    ),
                ),
                variables = mapOf("document" to JsVariableInfo(type = "Document")),
            ),
        )
        index.loadNpmPackage(
            "react-markdown",
            ParsedSymbols(variables = mapOf("default" to JsVariableInfo(type = "MarkdownComponent"))),
        )
        index.setLoaded(true)
        return index
    }

    @Test
    fun documentDotRoutesToGlobalMembers() {
        val route = InteropCompletionRouter.route(
            InteropCompletionIntent(
                kind = InteropCompletionKind.Js,
                typedChar = '.',
                logicalPrefix = "js/document.",
                receiverSegments = listOf("document"),
                hadTerminalDot = true,
            ),
            index(),
            emptyMap(),
        )

        assertEquals(InteropCompletionRouteKind.JsGlobalMembers, route.kind)
        assertEquals("Document", route.receiverType)
    }

    @Test
    fun invalidSlashRoutesToNone() {
        val route = InteropCompletionRouter.route(
            InteropCompletionIntent(
                kind = InteropCompletionKind.None,
                typedChar = '/',
                logicalPrefix = "js/document",
                hadInvalidSlash = true,
            ),
            index(),
            emptyMap(),
        )

        assertEquals(InteropCompletionRouteKind.None, route.kind)
    }

    @Test
    fun npmAliasDotRoutesToExportMembers() {
        val route = InteropCompletionRouter.route(
            InteropCompletionIntent(
                kind = InteropCompletionKind.NpmAlias,
                typedChar = '.',
                logicalPrefix = "Markdown/default.",
                hadTerminalDot = true,
                namespace = "Markdown",
                exportName = "default",
            ),
            index(),
            mapOf("Markdown" to "react-markdown"),
        )

        assertEquals(InteropCompletionRouteKind.NpmExportMembers, route.kind)
        assertEquals("MarkdownComponent", route.receiverType)
    }
}
