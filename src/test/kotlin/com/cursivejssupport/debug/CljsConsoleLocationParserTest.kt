package com.cursivejssupport.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CljsConsoleLocationParserTest {

    @Test
    fun `parses a ClojureScript stack frame`() {
        val line = "    at app.core.render (/workspace/src/app/core.cljs:42:7)"

        assertEquals(
            CljsConsoleLocation(
                path = "/workspace/src/app/core.cljs",
                line = 41,
                column = 6,
                startOffset = line.indexOf('/'),
                endOffset = line.indexOf(')'),
            ),
            CljsConsoleLocationParser.find(line),
        )
    }

    @Test
    fun `parses file URLs and JavaScript module extensions`() {
        val line = "at file:///workspace/out/app.mjs:3:1"

        assertEquals("file:///workspace/out/app.mjs", CljsConsoleLocationParser.find(line)?.path)
    }

    @Test
    fun `parses Windows paths`() {
        val line = "at render (C:\\workspace\\src\\app.cljs:12:4)"

        assertEquals("C:\\workspace\\src\\app.cljs", CljsConsoleLocationParser.find(line)?.path)
    }

    @Test(timeout = 1_000)
    fun `rejects a long REPL classpath without quadratic scanning`() {
        val line = buildString {
            append("/opt/homebrew/opt/java/bin/java -classpath ")
            repeat(1_000) { index ->
                append("/Users/test/.m2/repository/example/library-")
                append(index)
                append(".jar:")
            }
            append("clojure.main")
        }

        assertNull(CljsConsoleLocationParser.find(line))
    }
}
