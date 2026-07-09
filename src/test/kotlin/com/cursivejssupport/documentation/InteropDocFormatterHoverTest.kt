package com.cursivejssupport.documentation

import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropDocFormatterHoverTest {

    private val createRange = JsMember(kind = "method", returns = "Range", doc = "Creates a Range.")
    private val fetchMember = JsMember(
        kind = "method",
        returns = "Promise<Response>",
        params = listOf(
            JsParam(name = "input", type = "RequestInfo"),
            JsParam(name = "init", type = "RequestInit", optional = true),
        ),
        doc = "Starts fetching.\n\n@param input the resource\n\n@returns the eventual response\n\n" +
            "@example (js/fetch \"/api\")\n\n@deprecated prefer fetchLater",
    )

    @Test fun `member hover shows all overloads with primary bolded`() {
        val o2 = JsMember(kind = "method", returns = "Range", params = listOf(JsParam("x", "number")))
        val html = InteropDocFormatter.format(
            InteropDocSubject.Member(
                name = "createRange",
                asProperty = false,
                declaringType = "Document",
                member = createRange,
                overloads = listOf(createRange, o2),
            ),
        )!!
        assertTrue(html.contains("<b>createRange(): Range</b>"))
        assertTrue(html.contains("createRange(x: number): Range"))
        assertTrue(html.contains("declared in Document"))
    }

    @Test fun `overloads are capped with a more line`() {
        val overloads = (1..12).map { i ->
            JsMember(kind = "method", returns = "void", params = listOf(JsParam("a$i", "string")))
        }
        val html = InteropDocFormatter.format(
            InteropDocSubject.Member(
                name = "on", asProperty = false, declaringType = "Element",
                member = overloads.first(), overloads = overloads,
            ),
        )!!
        assertTrue(html.contains("+4 more overloads"))
        assertFalse(html.contains("a9:"))
    }

    @Test fun `cljs call line uses receiver mnemonic`() {
        val html = InteropDocFormatter.format(
            InteropDocSubject.Member(
                name = "createRange", asProperty = false, declaringType = "Document", member = createRange,
            ),
        )!!
        assertTrue(html.contains("(.createRange doc)"))
    }

    @Test fun `cljs call line prefers actual receiver text`() {
        val html = InteropDocFormatter.format(
            InteropDocSubject.Member(
                name = "createRange", asProperty = false, declaringType = "Document",
                member = createRange, receiverText = "my-doc",
            ),
        )!!
        assertTrue(html.contains("(.createRange my-doc)"))
    }

    @Test fun `property hover renders dash form`() {
        val prop = JsMember(kind = "property", type = "HTMLElement")
        val html = InteropDocFormatter.format(
            InteropDocSubject.Member(
                name = "body", asProperty = true, declaringType = "Document", member = prop,
            ),
        )!!
        assertTrue(html.contains("body: HTMLElement"))
        assertTrue(html.contains("(.-body doc)"))
    }

    @Test fun `global function hover carries tags`() {
        val html = InteropDocFormatter.format(
            InteropDocSubject.JsFunction("fetch", fetchMember),
        )!!
        assertTrue(html.contains("js/fetch(input: RequestInfo, init?: RequestInit): Promise&lt;Response&gt;"))
        assertTrue("cljs line: $html", html.contains("(js/fetch input init?)"))
        assertTrue(html.contains("<b>Deprecated.</b>"))
        assertTrue(html.contains("prefer fetchLater"))
        assertTrue(html.contains("the resource"))                // @param merged into row
        assertTrue(html.contains("the eventual response"))       // @returns description
        assertTrue(html.contains("<pre><code>"))                 // @example block
        assertFalse(html.contains("@deprecated"))                // raw tags not leaked
        assertFalse(html.contains("@param"))
    }

    @Test fun `constructor subject renders new signatures`() {
        val ctor = JsMember(kind = "method", returns = "Fuse", params = listOf(JsParam("list", "T[]")))
        val html = InteropDocFormatter.format(
            InteropDocSubject.Constructor("Fuse", listOf(ctor)),
        )!!
        assertTrue(html.contains("new Fuse(list: T[]): Fuse"))
        assertTrue(html.contains("(Fuse. list)"))
        assertTrue(html.contains("constructor"))
    }

    @Test fun `npm export with overloads renders as function`() {
        val fn = JsMember(kind = "method", returns = "string", params = listOf(JsParam("date", "Date")))
        val html = InteropDocFormatter.format(
            InteropDocSubject.NpmExport(
                packageName = "date-fns", exportName = "formatISO", type = "string",
                overloads = listOf(fn), doc = "Formats a date.",
            ),
        )!!
        assertTrue(html.contains("formatISO(date: Date): string"))
        assertTrue(html.contains("(formatISO date)"))
        assertTrue(html.contains("export from date-fns"))
        assertTrue(html.contains("Formats a date."))
    }

    @Test fun `mdn link still surfaces as section row`() {
        val m = JsMember(kind = "method", returns = "Range", doc = "Creates a Range.\n\n[MDN Reference](https://developer.mozilla.org/x)")
        val html = InteropDocFormatter.format(
            InteropDocSubject.Member(name = "createRange", asProperty = false, declaringType = "Document", member = m),
        )!!
        assertTrue(html.contains("""<a href="https://developer.mozilla.org/x">Open on MDN</a>"""))
    }

    @Test fun `receiver mnemonics`() {
        assertEquals("doc", InteropDocFormatter.receiverMnemonic("Document"))
        assertEquals("el", InteropDocFormatter.receiverMnemonic("HTMLDivElement"))
        assertEquals("el", InteropDocFormatter.receiverMnemonic("Element"))
        assertEquals("win", InteropDocFormatter.receiverMnemonic("Window"))
        assertEquals("obj", InteropDocFormatter.receiverMnemonic("CSSStyleDeclaration"))
    }

    @Test fun `unknown subject yields null`() {
        assertEquals(null, InteropDocFormatter.format(InteropDocSubject.Unknown))
    }

    @Test fun `ambiguous member renders all alternatives grouped by interface`() {
        val clear = JsMember(kind = "method", returns = "void", doc = "Empties the store.")
        val html = InteropDocFormatter.format(
            InteropDocSubject.Member(
                name = "clear", asProperty = false, declaringType = "Storage", member = clear,
                alternatives = listOf(
                    InteropDocSubject.MemberGroup(
                        "DOMTokenList",
                        listOf(
                            JsMember(kind = "method", returns = "void"),
                            JsMember(kind = "method", returns = "void", params = listOf(JsParam("a", "string"))),
                            JsMember(kind = "method", returns = "void", params = listOf(JsParam("b", "number"))),
                        ),
                    ),
                    InteropDocSubject.MemberGroup(
                        "Map",
                        listOf(JsMember(kind = "method", returns = "void")),
                    ),
                ),
            ),
        )!!
        assertTrue(html.contains("declared in Storage — receiver type unknown, +2 other candidates"))
        assertTrue(html.contains("<b>Also declared in</b>"))
        assertTrue(html.contains("<code>DOMTokenList</code> — clear(): void; clear(a: string): void"))
        assertTrue(html.contains("<i>(+1 overload)</i>"))
        assertTrue(html.contains("<code>Map</code> — clear(): void"))
        // Primary keeps its full treatment.
        assertTrue(html.contains("Empties the store."))
        assertTrue(html.contains("(.clear obj)"))
    }

    @Test fun `alternative groups are capped with a names-only overflow line`() {
        val clear = JsMember(kind = "method", returns = "void")
        val alternatives = ('A'..'T').map { c ->
            InteropDocSubject.MemberGroup("Iface$c", listOf(clear))
        }
        val html = InteropDocFormatter.format(
            InteropDocSubject.Member(
                name = "clear", asProperty = false, declaringType = "Storage", member = clear,
                alternatives = alternatives,
            ),
        )!!
        // 20 alternatives: 6 rendered in full, 14 collapse to a names-only line capped at 10.
        assertTrue(html.contains("<code>IfaceF</code>"))
        assertFalse(html.contains("<code>IfaceG</code>"))
        assertTrue(html.contains("+14 more: IfaceG, IfaceH, IfaceI, IfaceJ, IfaceK, IfaceL, IfaceM, IfaceN, IfaceO, IfaceP, …"))
        assertFalse(html.contains("IfaceQ"))
    }

    @Test fun `member without alternatives renders classic declared-in line`() {
        val html = InteropDocFormatter.format(
            InteropDocSubject.Member(
                name = "createRange", asProperty = false, declaringType = "Document", member = createRange,
            ),
        )!!
        assertTrue(html.contains("declared in Document"))
        assertFalse(html.contains("receiver type unknown"))
        assertFalse(html.contains("Also declared in"))
    }

    @Test fun `plain member without docs still renders definition`() {
        val html = InteropDocFormatter.format(
            InteropDocSubject.Member(
                name = "focus", asProperty = false, declaringType = "HTMLElement",
                member = JsMember(kind = "method", returns = "void"),
            ),
        )
        assertNotNull(html)
        assertTrue(html!!.contains("focus(): void"))
    }
}
