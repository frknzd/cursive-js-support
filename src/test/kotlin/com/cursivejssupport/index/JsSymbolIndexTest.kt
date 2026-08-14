package com.cursivejssupport.index

import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsParam
import com.cursivejssupport.parser.JsVariableInfo
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSymbolIndexTest {

    @Test
    fun `publishes complete snapshots atomically`() {
        val current = JsSymbolIndex().apply {
            load(ParsedSymbols(variables = mapOf("old" to JsVariableInfo(type = "Old"))))
            setLoaded(true)
        }
        val replacement = JsSymbolIndex().apply {
            load(ParsedSymbols(variables = mapOf("new" to JsVariableInfo(type = "New"))))
        }

        assertEquals("Old", current.resolveGlobalType("old"))
        assertEquals(null, current.resolveGlobalType("new"))
        current.publish(replacement)
        assertEquals(null, current.resolveGlobalType("old"))
        assertEquals("New", current.resolveGlobalType("new"))
    }

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
    fun `package completion snapshot excludes goog namespaces`() {
        val index = JsSymbolIndex()
        val exported = ParsedSymbols(variables = mapOf("value" to JsVariableInfo(type = "string")))
        index.loadNpmPackage("react", exported)
        index.loadNpmPackage("goog.string", exported)
        index.setLoaded(true)

        assertEquals(setOf("react"), index.npmPackageNames())
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
    fun genericSubstitutionProjectsMemberTypes() {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Box" to JsInterface(
                        typeParams = listOf("T"),
                        members = mapOf(
                            "value" to listOf(JsMember(kind = "property", type = "T")),
                            "map" to listOf(JsMember(kind = "method", returns = "Box<T>")),
                        ),
                    ),
                    "Response" to JsInterface(
                        members = mapOf("ok" to listOf(JsMember(kind = "property", type = "boolean"))),
                    ),
                ),
                variables = mapOf("box" to JsVariableInfo(type = "Box<Response>")),
            ),
        )

        assertEquals("Response", index.resolveJsChainType(listOf("box", "value")))
        assertEquals("boolean", index.resolveJsChainType(listOf("box", "value", "ok")))
        assertEquals(
            "Box<Response>",
            index.resolveJsChainTypeRef(listOf("box", "map"))?.display(),
        )
    }

    @Test
    fun canonicalTypeHandlesGenericsArraysAndUnions() {
        val index = JsSymbolIndex()
        assertEquals("Promise", index.canonicalType("Promise<Response>"))
        assertEquals("Array", index.canonicalType("Element[]"))
        assertEquals("Node", index.canonicalType("Node|null"))
        assertEquals("Window", index.canonicalType("Window&any"))
        assertEquals("Document", index.canonicalType("Document"))
    }

    @Test
    fun unionAliasesExpandForMemberResolution() {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Blob" to JsInterface(
                        members = mapOf("size" to listOf(JsMember(kind = "property", type = "number"))),
                    ),
                    "Body" to JsInterface(
                        members = mapOf("payload" to listOf(JsMember(kind = "property", type = "BodyInit"))),
                    ),
                ),
                variables = mapOf("req" to JsVariableInfo(type = "Body")),
                aliases = mapOf("BodyInit" to "Blob|string"),
            ),
        )
        assertEquals("Blob", index.canonicalType("BodyInit"))
        assertEquals("Blob", index.resolveJsChainType(listOf("req", "payload")))
        assertEquals("number", index.resolveJsChainType(listOf("req", "payload", "size")))
    }

    @Test
    fun aliasCyclesAreCapped() {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                aliases = mapOf("A" to "B|null", "B" to "A|null"),
            ),
        )
        // Must terminate; the exact result is unimportant.
        index.canonicalType("A")
    }

    @Test
    fun legacySchemaWithoutNewFieldsStillLoads() {
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val legacyJson = """
            {"interfaces":{"Doc":{"location":null,"extends":[],"members":{"title":[{"kind":"property","type":"string"}]}}},
             "variables":{"doc":{"type":"Doc"}},
             "functions":{}}
        """.trimIndent()
        val symbols = mapper.readValue(legacyJson, com.cursivejssupport.parser.ParsedSymbols::class.java)
        val index = JsSymbolIndex()
        index.load(symbols)
        assertEquals("string", index.resolveJsChainType(listOf("doc", "title")))
        assertTrue(symbols.interfaces["Doc"]!!.typeParams.isEmpty())
        assertTrue(symbols.aliases.isEmpty())
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

    @Test
    fun callableIndexAndOptionalMetadataParticipateInTypeFlow() {
        val index = JsSymbolIndex()
        index.load(
            ParsedSymbols(
                interfaces = mapOf(
                    "Callable" to JsInterface(
                        typeParams = listOf("T"),
                        members = mapOf(
                            "${'$'}call" to listOf(JsMember(params = listOf(JsParam("value", "T")), returns = "T[]")),
                            "${'$'}index:string" to listOf(JsMember(kind = "property", type = "T")),
                            "optional" to listOf(JsMember(kind = "property", type = "T", optional = true)),
                        ),
                    ),
                ),
            ),
        )
        val type = JsTypeRef.parse("Callable<Response>")

        assertEquals("Response[]", index.resolveCallSignatures(type).single().returns)
        assertEquals("Response", index.resolveIndexedValueType(type, numeric = false)?.display())
        assertEquals("Response | undefined", index.resolveMembersOf(type)["optional"]?.first
            ?.let(index::memberValueType)?.let { index.substitute(it, index.substitutionFor(type)) }?.display())
        assertTrue("${'$'}call" !in index.resolveMembersOf(type))
    }

    @Test
    fun unionsExposeOnlySafeSharedMembersWhileIntersectionsExposeAllMembers() {
        val index = JsSymbolIndex()
        index.load(ParsedSymbols(interfaces = mapOf(
            "A" to JsInterface(members = mapOf(
                "shared" to listOf(JsMember(kind = "property", type = "string")),
                "onlyA" to listOf(JsMember(kind = "property", type = "boolean")),
            )),
            "B" to JsInterface(members = mapOf(
                "shared" to listOf(JsMember(kind = "property", type = "number")),
                "onlyB" to listOf(JsMember(kind = "property", type = "boolean")),
            )),
        )))

        val union = index.resolveMembersOf(JsTypeRef.parse("A | B"))
        val intersection = index.resolveMembersOf(JsTypeRef.parse("A & B"))

        assertEquals(setOf("shared"), union.keys)
        assertEquals("string | number", union["shared"]?.first?.type)
        assertEquals(setOf("shared", "onlyA", "onlyB"), intersection.keys)
    }
}
