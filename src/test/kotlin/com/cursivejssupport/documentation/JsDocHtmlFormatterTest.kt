package com.cursivejssupport.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsDocHtmlFormatterTest {

    @Test
    fun `null or blank returns null`() {
        assertNull(JsDocHtmlFormatter.format(null))
        assertNull(JsDocHtmlFormatter.format(""))
        assertNull(JsDocHtmlFormatter.format("   \n\n"))
    }

    @Test
    fun `mdn reference becomes anchor`() {
        val html = JsDocHtmlFormatter.format(
            "The Document method. [MDN Reference](https://developer.mozilla.org/docs/Web/API/Document/createTreeWalker)"
        )!!
        assertTrue("expected anchor tag in $html",
            html.contains("""<a href="https://developer.mozilla.org/docs/Web/API/Document/createTreeWalker">MDN Reference</a>""")
        )
    }

    @Test
    fun `inline code becomes code tag`() {
        val html = JsDocHtmlFormatter.format("Returns `null` when there is no match")!!
        assertTrue(html.contains("<code>null</code>"))
    }

    @Test
    fun `bold and italics are converted`() {
        val html = JsDocHtmlFormatter.format("This is **important** and *very* useful")!!
        assertTrue(html.contains("<b>important</b>"))
        assertTrue(html.contains("<i>very</i>"))
    }

    @Test
    fun `angle brackets are escaped before substitution`() {
        val html = JsDocHtmlFormatter.format("Returns <div> element")!!
        assertTrue("expected escaped < in $html", html.contains("&lt;div&gt;"))
    }

    @Test
    fun `fenced code blocks are removed`() {
        val html = JsDocHtmlFormatter.format(
            """
            Description.
            ```ts
            const x = 1;
            ```
            More text.
            """.trimIndent()
        )!!
        assertTrue(html.contains("Description"))
        assertTrue(html.contains("More text"))
        assertTrue("expected ts block stripped", !html.contains("const x = 1"))
    }

    @Test
    fun `repeated mdn reference lines collapse to one`() {
        val html = JsDocHtmlFormatter.format(
            """
            Some doc.
            [MDN Reference](https://developer.mozilla.org/docs/Web/API/A)
            [MDN Reference](https://developer.mozilla.org/docs/Web/API/A)
            """.trimIndent()
        )!!
        val occurrences = Regex("MDN Reference").findAll(html).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun `ampersand is escaped`() {
        val html = JsDocHtmlFormatter.format("A & B")!!
        assertTrue("expected &amp; in $html", html.contains("A &amp; B"))
    }

    @Test
    fun `link with safely escaped surrounding text`() {
        val html = JsDocHtmlFormatter.format("See [docs](https://example.com/x) for <details>")!!
        assertTrue(html.contains("""<a href="https://example.com/x">docs</a>"""))
        assertTrue(html.contains("&lt;details&gt;"))
    }
}
