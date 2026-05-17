package com.cursivejssupport.parser

import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DtsParserTest {

    @Test
    fun createRangeMemberOffsetMatchesIdentifier() {
        val nodeExecutable = DtsParser.findNodeExecutable()
        assumeNotNull(nodeExecutable)

        val fixture = File("src/test/resources/parser/dom-lib-like.d.ts")
        assumeTrue("fixture missing", fixture.isFile)

        val source = fixture.readText()
        val key = fixture.absolutePath

        DtsParser(nodeExecutable!!).use { parser ->
            val symbols = parser.parse(mapOf(key to source))
            val loc = symbols.interfaces["DomDocLike"]?.members?.get("createRange")?.firstOrNull()?.location
            assertNotNull(loc)
            val offset = loc!!.offset
            assertTrue(
                "offset $offset should land on identifier 'createRange'",
                offset >= 0 && offset + "createRange".length <= source.length &&
                    source.substring(offset, offset + "createRange".length) == "createRange"
            )
            assertNotNull(symbols.interfaces["DomDocLike"]?.members?.get("createRange")?.firstOrNull()?.doc)
        }
    }

    @Test
    fun testParseSimpleDts() {
        val nodeExecutable = DtsParser.findNodeExecutable()
        assumeNotNull(nodeExecutable)

        DtsParser(nodeExecutable!!).use { parser ->
            val dtsContent = """
                /** My interface */
                interface MyInterface {
                    /** My property */
                    prop: string;
                    /** My method */
                    method(a: number): boolean;
                }
                /** My global */
                declare const myGlobal: MyInterface;
                /** My function */
                declare function myFunc(x: string): void;
            """.trimIndent()

            val symbols = parser.parse(mapOf("test.d.ts" to dtsContent))

            assertTrue(symbols.interfaces.containsKey("MyInterface"))
            val iface = symbols.interfaces["MyInterface"]!!
            assertTrue(iface.members.containsKey("prop"))
            assertEquals("string", iface.members["prop"]?.first()?.type)

            assertTrue(iface.members.containsKey("method"))
            assertEquals("boolean", iface.members["method"]?.first()?.returns)

            assertTrue(symbols.variables.containsKey("myGlobal"))
            assertEquals("MyInterface", symbols.variables["myGlobal"]?.type)

            assertTrue(symbols.functions.containsKey("myFunc"))
            assertEquals("void", symbols.functions["myFunc"]?.first()?.returns)

            assertNotNull(symbols.interfaces["MyInterface"]?.members?.get("prop")?.firstOrNull()?.doc)
            assertNotNull(symbols.variables["myGlobal"]?.doc)
            assertNotNull(symbols.functions["myFunc"]?.firstOrNull()?.doc)
        }
    }

    @Test
    fun parsesInterfaceHeritageClauses() {
        val nodeExecutable = DtsParser.findNodeExecutable()
        assumeNotNull(nodeExecutable)

        DtsParser(nodeExecutable!!).use { parser ->
            val dtsContent = """
                interface AbstractRange {
                    readonly startContainer: Node;
                }
                interface Range extends AbstractRange {
                    cloneContents(): DocumentFragment;
                }
            """.trimIndent()

            val symbols = parser.parse(mapOf("range.d.ts" to dtsContent))
            assertEquals(listOf("AbstractRange"), symbols.interfaces["Range"]?.extends)
            assertTrue(symbols.interfaces["AbstractRange"]?.members?.containsKey("startContainer") == true)
        }
    }

    @Test
    fun testParseMultipleFiles() {
        val nodeExecutable = DtsParser.findNodeExecutable()
        assumeNotNull(nodeExecutable)

        DtsParser(nodeExecutable!!).use { parser ->
            val file1 = "interface A { propA: string; }"
            val file2 = "interface B { propB: number; }"
            val file3 = "declare const a: A; declare const b: B;"

            val symbols1 = parser.parse(mapOf("file1.d.ts" to file1))
            val symbols2 = parser.parse(mapOf("file2.d.ts" to file2))
            val symbols3 = parser.parse(mapOf("file3.d.ts" to file3))

            // In real usage, JsSymbolIndex handles merging, but we want to make sure
            // each parse call returns the correct subset of symbols.
            
            assertTrue(symbols1.interfaces.containsKey("A"))
            assertFalse(symbols1.interfaces.containsKey("B"))
            
            assertTrue(symbols2.interfaces.containsKey("B"))
            assertFalse(symbols2.interfaces.containsKey("A"))
            
            assertTrue(symbols3.variables.containsKey("a"))
            assertTrue(symbols3.variables.containsKey("b"))
        }
    }

    @Test
    fun parsesClassesAndStatics() {
        val nodeExecutable = DtsParser.findNodeExecutable()
        assumeNotNull(nodeExecutable)

        DtsParser(nodeExecutable!!).use { parser ->
            val dtsContent = """
                declare class Fuse<T> {
                  public constructor(list: T[]);
                  public search(pattern: string): T[];
                  public static version: string;
                  public static createIndex<U>(list: U[]): any;
                }
                export { Fuse as default };
            """.trimIndent()

            val symbols = parser.parse(mapOf("fuse.d.ts" to dtsContent))

            // Instance interface
            assertTrue(symbols.interfaces.containsKey("Fuse"))
            val instanceMembers = symbols.interfaces["Fuse"]!!.members
            assertTrue(instanceMembers.containsKey("search"))
            
            // Static interface
            assertTrue(symbols.interfaces.containsKey("TYPE${'$'}Fuse${'$'}Static"))
            val staticMembers = symbols.interfaces["TYPE${'$'}Fuse${'$'}Static"]!!.members
            assertTrue(staticMembers.containsKey("new"))
            assertTrue(staticMembers.containsKey("version"))
            assertTrue(staticMembers.containsKey("createIndex"))
            
            // Global variable for the class points to static type
            assertEquals("TYPE${'$'}Fuse${'$'}Static", symbols.variables["Fuse"]?.type)
            
            // Default export points to static type (because Fuse was resolved to static type)
            assertEquals("TYPE${'$'}Fuse${'$'}Static", symbols.variables["default"]?.type)
        }
    }

    @Test
    fun parsesExportEquals() {
        val nodeExecutable = DtsParser.findNodeExecutable()
        assumeNotNull(nodeExecutable)

        DtsParser(nodeExecutable!!).use { parser ->
            val dtsContent = """
                declare namespace ReactDOM {
                  function render(element: any, container: any): void;
                }
                export = ReactDOM;
            """.trimIndent()

            val symbols = parser.parse(mapOf("react-dom.d.ts" to dtsContent))
            
            assertTrue(symbols.interfaces.containsKey("NAMESPACE${'$'}ReactDOM"))
            assertEquals("NAMESPACE${'$'}ReactDOM", symbols.variables["ReactDOM"]?.type)
            assertEquals("NAMESPACE${'$'}ReactDOM", symbols.variables["default"]?.type)
        }
    }
}
