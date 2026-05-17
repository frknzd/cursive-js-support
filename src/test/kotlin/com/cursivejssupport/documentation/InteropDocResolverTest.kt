package com.cursivejssupport.documentation

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NpmBinding
import com.cursivejssupport.npm.NpmBindingKind
import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsParam
import com.cursivejssupport.parser.JsVariableInfo
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropDocResolverTest {

    private fun buildIndex(): JsSymbolIndex {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Document" to JsInterface(
                        members = mapOf(
                            "createRange" to listOf(
                                JsMember(
                                    kind = "method",
                                    returns = "Range",
                                    params = listOf(JsParam(name = "root", type = "Node")),
                                    doc = "Creates a Range. [MDN Reference](https://developer.mozilla.org/docs/Web/API/Document/createRange)"
                                )
                            ),
                            "createTreeWalker" to listOf(
                                JsMember(
                                    kind = "method",
                                    returns = "TreeWalker",
                                    params = listOf(
                                        JsParam(name = "root", type = "Node"),
                                        JsParam(name = "whatToShow", type = "number", optional = true),
                                    ),
                                    doc = "Creates a TreeWalker. [MDN Reference](https://developer.mozilla.org/docs/Web/API/Document/createTreeWalker)"
                                )
                            ),
                        )
                    ),
                    "Range" to JsInterface(
                        members = mapOf(
                            "commonAncestorContainer" to listOf(
                                JsMember(kind = "property", type = "Node", doc = "The deepest common ancestor.")
                            )
                        )
                    ),
                    // `NodeFilter` (the interface) only owns `acceptNode`. The constants like
                    // SHOW_ELEMENT live on the typeof-companion `TYPE$NodeFilter` instead. This
                    // models exactly the cross-talk case reported by the user.
                    "NodeFilter" to JsInterface(
                        members = mapOf(
                            "acceptNode" to listOf(JsMember(kind = "method", returns = "number"))
                        )
                    ),
                    "TYPE\$NodeFilter" to JsInterface(
                        members = mapOf(
                            "SHOW_ELEMENT" to listOf(JsMember(kind = "property", type = "number")),
                            "acceptNode" to listOf(JsMember(kind = "method", returns = "number"))
                        )
                    ),
                ),
                variables = mapOf(
                    "document" to JsVariableInfo(type = "Document", doc = "Global document object."),
                    "NodeFilter" to JsVariableInfo(type = "TYPE\$NodeFilter"),
                ),
                functions = mapOf(
                    "fetch" to listOf(
                        JsMember(
                            kind = "method",
                            returns = "Promise",
                            params = listOf(JsParam(name = "url", type = "string")),
                        )
                    )
                ),
            )
        )
        index.setLoaded(true)
        return index
    }

    @Test
    fun `resolves js global`() {
        val subject = InteropDocResolver.resolveFromParts(
            namespace = "js", name = "document",
            receiverType = null, aliases = emptyMap(), index = buildIndex(),
        )
        assertTrue(subject is InteropDocSubject.JsGlobal)
        subject as InteropDocSubject.JsGlobal
        assertEquals("document", subject.name)
        assertEquals("Document", subject.info.type)
    }

    @Test
    fun `resolves js function`() {
        val subject = InteropDocResolver.resolveFromParts(
            namespace = "js", name = "fetch",
            receiverType = null, aliases = emptyMap(), index = buildIndex(),
        )
        assertTrue(subject is InteropDocSubject.JsFunction)
        subject as InteropDocSubject.JsFunction
        assertEquals("fetch", subject.name)
        assertEquals("Promise", subject.overload.returns)
    }

    @Test
    fun `resolves js chain through global member`() {
        val subject = InteropDocResolver.resolveFromParts(
            namespace = "js", name = "document.createRange",
            receiverType = null, aliases = emptyMap(), index = buildIndex(),
        )
        assertTrue(subject is InteropDocSubject.JsChain)
        subject as InteropDocSubject.JsChain
        assertEquals(listOf("document", "createRange"), subject.segments)
        assertEquals("Document", subject.declaringType)
        assertEquals("Range", subject.member.returns)
    }

    @Test
    fun `dot property with known receiver type`() {
        val subject = InteropDocResolver.resolveFromParts(
            namespace = null, name = ".-commonAncestorContainer",
            receiverType = "Range", aliases = emptyMap(), index = buildIndex(),
        )
        assertTrue(subject is InteropDocSubject.Member)
        subject as InteropDocSubject.Member
        assertEquals(true, subject.asProperty)
        assertEquals("Range", subject.declaringType)
        assertEquals("Node", subject.member.type)
    }

    @Test
    fun `dot property falls through to prefix sampler when receiver type is unknown`() {
        val subject = InteropDocResolver.resolveFromParts(
            namespace = null, name = ".-commonAncestorContainer",
            receiverType = null, aliases = emptyMap(), index = buildIndex(),
        )
        assertTrue(subject is InteropDocSubject.Member)
        subject as InteropDocSubject.Member
        assertEquals(true, subject.asProperty)
        assertEquals("commonAncestorContainer", subject.name)
        assertEquals("Range", subject.declaringType)
    }

    @Test
    fun `typeof companion is sanitized and interface check happens first`() {
        // SHOW_ELEMENT only exists on TYPE$NodeFilter (companion), so the lookup falls through to
        // the companion — but the declaring type is presented as `NodeFilter` (sanitized).
        val subject = InteropDocResolver.resolveFromParts(
            namespace = null, name = ".-SHOW_ELEMENT",
            receiverType = "TYPE\$NodeFilter", aliases = emptyMap(), index = buildIndex(),
        )
        assertTrue(subject is InteropDocSubject.Member)
        subject as InteropDocSubject.Member
        assertEquals("NodeFilter", subject.declaringType)
        assertEquals("SHOW_ELEMENT", subject.name)
    }

    @Test
    fun `typeof companion prefers interface match when both exist`() {
        // acceptNode exists on BOTH the interface and the companion; the resolver must prefer the
        // interface. The returned member should be from `NodeFilter`, not `TYPE$NodeFilter`.
        val subject = InteropDocResolver.resolveFromParts(
            namespace = null, name = ".acceptNode",
            receiverType = "TYPE\$NodeFilter", aliases = emptyMap(), index = buildIndex(),
        )
        assertTrue(subject is InteropDocSubject.Member)
        subject as InteropDocSubject.Member
        assertEquals("NodeFilter", subject.declaringType)
        assertEquals(false, subject.asProperty)
    }

    @Test
    fun `unknown member returns Unknown`() {
        val subject = InteropDocResolver.resolveFromParts(
            namespace = null, name = ".-nothingExpected",
            receiverType = "Document", aliases = emptyMap(), index = buildIndex(),
        )
        assertEquals(InteropDocSubject.Unknown, subject)
    }

    @Test
    fun `resolves npm export through alias`() {
        val index = buildIndex()
        index.loadNpmPackage(
            "react",
            ParsedSymbols(
                variables = mapOf("useState" to JsVariableInfo(type = "Function"))
            )
        )
        val subject = InteropDocResolver.resolveFromParts(
            namespace = "react", name = "useState",
            receiverType = null,
            aliases = mapOf("react" to NpmBinding("react", NpmBindingKind.AS)),
            index = index,
        )
        assertTrue(subject is InteropDocSubject.NpmExport)
        subject as InteropDocSubject.NpmExport
        assertEquals("react", subject.packageName)
        assertEquals("useState", subject.exportName)
        assertEquals("Function", subject.type)
    }

    @Test
    fun `bare alias falls back to default export`() {
        val index = buildIndex()
        index.loadNpmPackage(
            "react-markdown",
            ParsedSymbols(
                variables = mapOf("default" to JsVariableInfo(type = "Function"))
            )
        )
        val subject = InteropDocResolver.resolveFromParts(
            namespace = null, name = "Markdown",
            receiverType = null,
            aliases = mapOf("Markdown" to NpmBinding("react-markdown", NpmBindingKind.DEFAULT)),
            index = index,
        )
        assertTrue(subject is InteropDocSubject.NpmExport)
        subject as InteropDocSubject.NpmExport
        assertEquals("default", subject.exportName)
    }

    @Test
    fun `sanitizeTypeName strips TYPE prefix`() {
        assertEquals("NodeFilter", InteropDocResolver.sanitizeTypeName("TYPE\$NodeFilter"))
        assertEquals("Range", InteropDocResolver.sanitizeTypeName("Range"))
    }

    @Test
    fun `formatter renders chain subject with definition and content`() {
        val index = buildIndex()
        val subject = InteropDocResolver.resolveFromParts(
            namespace = "js", name = "document.createRange",
            receiverType = null, aliases = emptyMap(), index = index,
        )
        val html = InteropDocFormatter.format(subject)
        assertNotNull(html)
        html!!
        assertTrue("expected signature in $html", html.contains("createRange(root: Node): Range"))
        assertTrue("expected declared-in", html.contains("declared in Document"))
        assertTrue("expected MDN link", html.contains("Open on MDN"))
    }
}
