package com.cursivejssupport.debug

import com.cursivejssupport.project.SourceMapPathMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SourceMapV3Test {
    @Test fun `decodes basic mappings and sources content`() {
        val map = SourceMapV3.parse(
            """{"version":3,"file":"app.js","sources":["src/app.cljs"],"sourcesContent":["(ns app)"],"names":[],"mappings":"AAAA;AACA"}""",
            "/work/out/app.js.map",
        )
        assertEquals("/work/out/src/app.cljs", map.generatedToOriginal(0, 0)?.source)
        assertEquals(1, map.generatedToOriginal(1, 0)?.line)
        assertEquals("(ns app)", map.sourcesContent["/work/out/src/app.cljs"])
        assertNotNull(map.originalToGenerated("/work/out/src/app.cljs", 1))
    }

    @Test fun `applies section offsets`() {
        val map = SourceMapV3.parse(
            """{"version":3,"sections":[{"offset":{"line":3,"column":4},"map":{"version":3,"sources":["a.cljs"],"names":[],"mappings":"AAAA"}}]}""",
            "/work/app.js.map",
        )
        assertEquals("/work/a.cljs", map.generatedToOriginal(3, 4)?.source)
    }

    @Test fun `reverse mapping uses the closest preceding source column`() {
        val map = SourceMapV3.parse(
            """{"version":3,"sources":["a.cljs"],"names":[],"mappings":"AAAA,KAAK"}""",
        )

        assertEquals(0, map.originalToGenerated("a.cljs", 0, 3)?.generatedColumn)
        assertEquals(5, map.originalToGenerated("a.cljs", 0, 5)?.generatedColumn)
    }

    @Test fun `applies remote path mappings`() {
        val map = SourceMapV3.parse(
            """{"version":3,"sources":["webpack:///src/a.cljs"],"names":[],"mappings":"AAAA"}""",
            pathMappings = listOf(SourceMapPathMapping("src", "/project/src")),
        )
        assertEquals("/project/src/a.cljs", map.generatedToOriginal(0, 0)?.source)
    }

    @Test fun `maps copied cljs sources under output directory back to project src`() {
        val map = SourceMapV3.parse(
            """{"version":3,"file":"/project/out/fixture/core.js","sources":["core.cljs"],"names":[],"mappings":"AAAA"}""",
            "/project/out/fixture/core.js.map",
            listOf(SourceMapPathMapping("/project/out", "/project/src")),
        )
        assertEquals("/project/src/fixture/core.cljs", map.generatedToOriginal(0, 0)?.source)
    }

    @Test fun `parses inline base64 map`() {
        val json = """{"version":3,"sources":["a.cljs"],"names":[],"mappings":"AAAA"}"""
        val url = "data:application/json;base64," + java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        assertEquals("a.cljs", SourceMapV3.parseInlineDataUrl(url).generatedToOriginal(0, 0)?.source)
    }
}
