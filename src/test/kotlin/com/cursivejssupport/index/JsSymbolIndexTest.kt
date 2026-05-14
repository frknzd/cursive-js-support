package com.cursivejssupport.index

import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsVariableInfo
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.*
import org.junit.Test

class JsSymbolIndexTest {

    @Test
    fun testMergeInterfaces() {
        val index = JsSymbolIndex()
        
        val symbols1 = ParsedSymbols(
            interfaces = mapOf(
                "MyIface" to JsInterface(
                    members = mapOf(
                        "prop1" to listOf(JsMember(kind = "property", type = "string"))
                    )
                )
            )
        )
        
        val symbols2 = ParsedSymbols(
            interfaces = mapOf(
                "MyIface" to JsInterface(
                    members = mapOf(
                        "prop2" to listOf(JsMember(kind = "property", type = "number"))
                    )
                )
            )
        )
        
        index.load(symbols1)
        index.load(symbols2)
        
        val iface = index.resolveInterface("MyIface")
        assertNotNull(iface)
        assertTrue(iface!!.members.containsKey("prop1"))
        assertTrue(iface.members.containsKey("prop2"))
        assertEquals("string", iface.members["prop1"]?.first()?.type)
        assertEquals("number", iface.members["prop2"]?.first()?.type)
    }

    @Test
    fun testMergeFunctions() {
        val index = JsSymbolIndex()
        val fn = com.cursivejssupport.parser.JsMember(kind = "method", returns = "void")
        index.load(ParsedSymbols(functions = mapOf("f1" to listOf(fn))))
        index.load(ParsedSymbols(functions = mapOf("f2" to listOf(fn))))
        assertNotNull(index.resolveFunctions("f1"))
        assertNotNull(index.resolveFunctions("f2"))
    }

    @Test
    fun testLoadNpmPackageMergesInterfaces() {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Dom" to JsInterface(members = mapOf("a" to listOf(JsMember(kind = "property", type = "number"))))
                )
            )
        )
        index.loadNpmPackage(
            "react",
            ParsedSymbols(
                interfaces = mapOf(
                    "Dom" to JsInterface(members = mapOf("b" to listOf(JsMember(kind = "property", type = "string"))))
                ),
                variables = mapOf("createElement" to JsVariableInfo(type = "any"))
            )
        )
        val dom = index.resolveInterface("Dom")
        assertNotNull(dom)
        assertTrue(dom!!.members.containsKey("a"))
        assertTrue(dom.members.containsKey("b"))
        assertTrue(index.hasMemberName("a"))
        assertTrue(index.hasMemberName("b"))
        assertTrue(index.isKnownNpmExport("react", "createElement"))
    }

    @Test
    fun resolveJsChainTypeWalksGlobalsAndMembers() {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Document" to JsInterface(
                        members = mapOf(
                            "createRange" to listOf(
                                JsMember(kind = "method", returns = "Range")
                            )
                        )
                    ),
                    "Range" to JsInterface(members = emptyMap())
                ),
                variables = mapOf(
                    "document" to JsVariableInfo(type = "Document")
                )
            )
        )
        assertEquals("Range", index.resolveJsChainType(listOf("document", "createRange")))
        assertEquals("Document", index.resolveJsChainType(listOf("document")))
    }

    @Test
    fun npmExportWithNullLocationIsStillKnown() {
        val index = JsSymbolIndex()
        index.loadNpmPackage(
            "demo-pkg",
            ParsedSymbols(
                variables = mapOf("default" to JsVariableInfo(type = "any", location = null)),
            ),
        )
        assertTrue(index.isKnownNpmExport("demo-pkg", "default"))
    }

    @Test
    fun resolveNpmExportTypeFromVariablesAndFunctions() {
        val index = JsSymbolIndex()
        index.loadNpmPackage(
            "react-markdown",
            ParsedSymbols(
                interfaces = mapOf(
                    "MarkdownComponent" to JsInterface(
                        members = mapOf(
                            "render" to listOf(JsMember(kind = "method", returns = "void")),
                        ),
                    ),
                ),
                variables = mapOf(
                    "default" to JsVariableInfo(type = "MarkdownComponent"),
                ),
            ),
        )
        assertEquals("MarkdownComponent", index.resolveNpmExportType("react-markdown", "default"))
        index.loadNpmPackage(
            "other",
            ParsedSymbols(
                functions = mapOf(
                    "factory" to listOf(JsMember(kind = "method", returns = "MarkdownComponent")),
                ),
            ),
        )
        assertEquals("MarkdownComponent", index.resolveNpmExportType("other", "factory"))
    }

    @Test
    fun inheritedMembersResolveFromBaseInterfaces() {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "AbstractRange" to JsInterface(
                        members = mapOf(
                            "startContainer" to listOf(JsMember(kind = "property", type = "Node")),
                        ),
                    ),
                    "Range" to JsInterface(
                        extends = listOf("AbstractRange"),
                        members = mapOf(
                            "cloneContents" to listOf(JsMember(kind = "method", returns = "DocumentFragment")),
                        ),
                    ),
                    "Node" to JsInterface(members = emptyMap()),
                    "DocumentFragment" to JsInterface(members = emptyMap()),
                ),
                variables = mapOf("range" to JsVariableInfo(type = "Range")),
            ),
        )

        val inherited = index.resolveMember("Range", "startContainer")
        assertNotNull(inherited)
        assertEquals("AbstractRange", inherited!!.declaringType)
        assertEquals("Node", inherited.first?.type)
        assertEquals("Node", index.resolveJsChainType(listOf("range", "startContainer")))
        assertTrue(index.hasMemberName("startContainer"))
    }
}
