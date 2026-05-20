package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsParam
import com.cursivejssupport.parser.JsVariableInfo
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that [JsSymbolIndex.resolveMembers] — the exact call site used by the completion
 * pipeline's `emitMembers()` — exposes the full set of available members for every major browser
 * global, including those inherited through multi-level prototype chains.
 *
 * The index built here models the real TypeScript inheritance hierarchy:
 *
 *   EventTarget ← Node ← Document
 *   EventTarget ← Node ← Element ← HTMLElement
 *   EventTarget ← Performance
 *   EventTarget ← XMLHttpRequest
 *   EventTarget ← WebSocket
 *   EventTarget ← Worker
 *   EventTarget ← MessagePort
 *   EventTarget ← MediaQueryList
 *   EventTarget + WindowOrWorkerGlobalScope ← Window
 *
 * These tests fail if the bundled index omits any `extends` declaration — they document the
 * minimum completeness contract for `js/<global>.` autocomplete.
 */
class JsGlobalMemberCompletenessTest {

    private lateinit var index: JsSymbolIndex

    @Before
    fun setUp() {
        index = buildCompletenessIndex()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Resolves a global variable name to its TypeScript type then calls resolveMembers. */
    private fun membersOf(globalName: String): Map<String, com.cursivejssupport.index.JsResolvedMember> {
        val type = index.resolveGlobalType(globalName) ?: error("No global '$globalName' in index")
        return index.resolveMembers(type)
    }

    private fun assertOwn(globalName: String, memberName: String) {
        val members = membersOf(globalName)
        val resolved = members[memberName]
        assertNotNull("$globalName should have own member '$memberName'", resolved)
        assertEquals(
            "$globalName.$memberName should be own (distance 0)",
            0,
            resolved!!.distance,
        )
    }

    private fun assertInherited(globalName: String, memberName: String, fromType: String) {
        val members = membersOf(globalName)
        val resolved = members[memberName]
        assertNotNull("$globalName should inherit '$memberName' from $fromType", resolved)
        assertTrue(
            "$globalName.$memberName should be inherited (distance > 0), got ${resolved!!.distance}",
            resolved.distance > 0,
        )
        assertEquals(
            "$globalName.$memberName should be declared in $fromType",
            fromType,
            resolved.declaringType,
        )
    }

    private fun assertMinMembers(globalName: String, minCount: Int) {
        val count = membersOf(globalName).size
        assertTrue(
            "js/$globalName. should expose >= $minCount members (got $count) — check inheritance chains",
            count >= minCount,
        )
    }

    // ─── EventTarget: the root of the browser event tree ─────────────────────

    @Test fun `EventTarget has addEventListener`() {
        assertOwn("EventTarget_iface", "addEventListener")
    }

    @Test fun `EventTarget has removeEventListener`() {
        assertOwn("EventTarget_iface", "removeEventListener")
    }

    @Test fun `EventTarget has dispatchEvent`() {
        assertOwn("EventTarget_iface", "dispatchEvent")
    }

    // ─── Window: own + EventTarget + WindowOrWorkerGlobalScope ───────────────

    @Test fun `window has own innerWidth`() {
        assertOwn("window", "innerWidth")
    }

    @Test fun `window has own innerHeight`() {
        assertOwn("window", "innerHeight")
    }

    @Test fun `window has own devicePixelRatio`() {
        assertOwn("window", "devicePixelRatio")
    }

    @Test fun `window has own scrollX`() {
        assertOwn("window", "scrollX")
    }

    @Test fun `window has own scrollY`() {
        assertOwn("window", "scrollY")
    }

    @Test fun `window inherits addEventListener from EventTarget`() {
        assertInherited("window", "addEventListener", "EventTarget")
    }

    @Test fun `window inherits removeEventListener from EventTarget`() {
        assertInherited("window", "removeEventListener", "EventTarget")
    }

    @Test fun `window inherits dispatchEvent from EventTarget`() {
        assertInherited("window", "dispatchEvent", "EventTarget")
    }

    @Test fun `window inherits setTimeout from WindowOrWorkerGlobalScope`() {
        assertInherited("window", "setTimeout", "WindowOrWorkerGlobalScope")
    }

    @Test fun `window inherits clearTimeout from WindowOrWorkerGlobalScope`() {
        assertInherited("window", "clearTimeout", "WindowOrWorkerGlobalScope")
    }

    @Test fun `window inherits setInterval from WindowOrWorkerGlobalScope`() {
        assertInherited("window", "setInterval", "WindowOrWorkerGlobalScope")
    }

    @Test fun `window inherits clearInterval from WindowOrWorkerGlobalScope`() {
        assertInherited("window", "clearInterval", "WindowOrWorkerGlobalScope")
    }

    @Test fun `window inherits fetch from WindowOrWorkerGlobalScope`() {
        assertInherited("window", "fetch", "WindowOrWorkerGlobalScope")
    }

    @Test fun `window inherits atob from WindowOrWorkerGlobalScope`() {
        assertInherited("window", "atob", "WindowOrWorkerGlobalScope")
    }

    @Test fun `window inherits btoa from WindowOrWorkerGlobalScope`() {
        assertInherited("window", "btoa", "WindowOrWorkerGlobalScope")
    }

    @Test fun `window inherits queueMicrotask from WindowOrWorkerGlobalScope`() {
        assertInherited("window", "queueMicrotask", "WindowOrWorkerGlobalScope")
    }

    @Test fun `window inherits structuredClone from WindowOrWorkerGlobalScope`() {
        assertInherited("window", "structuredClone", "WindowOrWorkerGlobalScope")
    }

    @Test fun `window exposes at least 25 members total`() {
        assertMinMembers("window", 25)
    }

    // ─── Document: own + Node + EventTarget ──────────────────────────────────

    @Test fun `document has own createElement`() {
        assertOwn("document", "createElement")
    }

    @Test fun `document has own getElementById`() {
        assertOwn("document", "getElementById")
    }

    @Test fun `document has own querySelector`() {
        assertOwn("document", "querySelector")
    }

    @Test fun `document has own querySelectorAll`() {
        assertOwn("document", "querySelectorAll")
    }

    @Test fun `document has own title property`() {
        assertOwn("document", "title")
    }

    @Test fun `document has own body property`() {
        assertOwn("document", "body")
    }

    @Test fun `document has own cookie property`() {
        assertOwn("document", "cookie")
    }

    @Test fun `document has own readyState property`() {
        assertOwn("document", "readyState")
    }

    @Test fun `document inherits nodeType from Node`() {
        assertInherited("document", "nodeType", "Node")
    }

    @Test fun `document inherits nodeName from Node`() {
        assertInherited("document", "nodeName", "Node")
    }

    @Test fun `document inherits appendChild from Node`() {
        assertInherited("document", "appendChild", "Node")
    }

    @Test fun `document inherits removeChild from Node`() {
        assertInherited("document", "removeChild", "Node")
    }

    @Test fun `document inherits cloneNode from Node`() {
        assertInherited("document", "cloneNode", "Node")
    }

    @Test fun `document inherits addEventListener from EventTarget`() {
        assertInherited("document", "addEventListener", "EventTarget")
    }

    @Test fun `document inherits removeEventListener from EventTarget`() {
        assertInherited("document", "removeEventListener", "EventTarget")
    }

    @Test fun `document inherits dispatchEvent from EventTarget`() {
        assertInherited("document", "dispatchEvent", "EventTarget")
    }

    @Test fun `document exposes at least 28 members total`() {
        assertMinMembers("document", 28)
    }

    // ─── performance: own + EventTarget ──────────────────────────────────────

    @Test fun `performance has own now`() {
        assertOwn("performance", "now")
    }

    @Test fun `performance has own mark`() {
        assertOwn("performance", "mark")
    }

    @Test fun `performance has own measure`() {
        assertOwn("performance", "measure")
    }

    @Test fun `performance has own getEntries`() {
        assertOwn("performance", "getEntries")
    }

    @Test fun `performance has own timeOrigin`() {
        assertOwn("performance", "timeOrigin")
    }

    @Test fun `performance inherits addEventListener from EventTarget`() {
        assertInherited("performance", "addEventListener", "EventTarget")
    }

    @Test fun `performance inherits removeEventListener from EventTarget`() {
        assertInherited("performance", "removeEventListener", "EventTarget")
    }

    @Test fun `performance inherits dispatchEvent from EventTarget`() {
        assertInherited("performance", "dispatchEvent", "EventTarget")
    }

    @Test fun `performance exposes at least 10 members total`() {
        assertMinMembers("performance", 10)
    }

    // ─── navigator: own members ───────────────────────────────────────────────

    @Test fun `navigator has userAgent`() {
        assertOwn("navigator", "userAgent")
    }

    @Test fun `navigator has language`() {
        assertOwn("navigator", "language")
    }

    @Test fun `navigator has onLine`() {
        assertOwn("navigator", "onLine")
    }

    @Test fun `navigator has cookieEnabled`() {
        assertOwn("navigator", "cookieEnabled")
    }

    @Test fun `navigator has hardwareConcurrency`() {
        assertOwn("navigator", "hardwareConcurrency")
    }

    @Test fun `navigator has sendBeacon`() {
        assertOwn("navigator", "sendBeacon")
    }

    @Test fun `navigator exposes at least 10 members total`() {
        assertMinMembers("navigator", 10)
    }

    // ─── location: own members ────────────────────────────────────────────────

    @Test fun `location has href`() {
        assertOwn("location", "href")
    }

    @Test fun `location has pathname`() {
        assertOwn("location", "pathname")
    }

    @Test fun `location has origin`() {
        assertOwn("location", "origin")
    }

    @Test fun `location has assign`() {
        assertOwn("location", "assign")
    }

    @Test fun `location has replace`() {
        assertOwn("location", "replace")
    }

    @Test fun `location has reload`() {
        assertOwn("location", "reload")
    }

    @Test fun `location exposes at least 10 members total`() {
        assertMinMembers("location", 10)
    }

    // ─── history: own members ─────────────────────────────────────────────────

    @Test fun `history has pushState`() {
        assertOwn("history", "pushState")
    }

    @Test fun `history has replaceState`() {
        assertOwn("history", "replaceState")
    }

    @Test fun `history has back`() {
        assertOwn("history", "back")
    }

    @Test fun `history has forward`() {
        assertOwn("history", "forward")
    }

    @Test fun `history has go`() {
        assertOwn("history", "go")
    }

    @Test fun `history has length`() {
        assertOwn("history", "length")
    }

    @Test fun `history exposes at least 7 members total`() {
        assertMinMembers("history", 7)
    }

    // ─── console: own members ─────────────────────────────────────────────────

    @Test fun `console has log`() {
        assertOwn("console", "log")
    }

    @Test fun `console has warn`() {
        assertOwn("console", "warn")
    }

    @Test fun `console has error`() {
        assertOwn("console", "error")
    }

    @Test fun `console has info`() {
        assertOwn("console", "info")
    }

    @Test fun `console has debug`() {
        assertOwn("console", "debug")
    }

    @Test fun `console has group`() {
        assertOwn("console", "group")
    }

    @Test fun `console has time`() {
        assertOwn("console", "time")
    }

    @Test fun `console has table`() {
        assertOwn("console", "table")
    }

    @Test fun `console exposes at least 15 members total`() {
        assertMinMembers("console", 15)
    }

    // ─── localStorage / sessionStorage: own members ───────────────────────────

    @Test fun `localStorage has getItem`() {
        assertOwn("localStorage", "getItem")
    }

    @Test fun `localStorage has setItem`() {
        assertOwn("localStorage", "setItem")
    }

    @Test fun `localStorage has removeItem`() {
        assertOwn("localStorage", "removeItem")
    }

    @Test fun `localStorage has clear`() {
        assertOwn("localStorage", "clear")
    }

    @Test fun `localStorage has key`() {
        assertOwn("localStorage", "key")
    }

    @Test fun `localStorage has length`() {
        assertOwn("localStorage", "length")
    }

    @Test fun `sessionStorage has getItem`() {
        assertOwn("sessionStorage", "getItem")
    }

    // ─── crypto: own members ──────────────────────────────────────────────────

    @Test fun `crypto has getRandomValues`() {
        assertOwn("crypto", "getRandomValues")
    }

    @Test fun `crypto has randomUUID`() {
        assertOwn("crypto", "randomUUID")
    }

    @Test fun `crypto has subtle`() {
        assertOwn("crypto", "subtle")
    }

    // ─── HTMLElement: own + Element + Node + EventTarget ─────────────────────

    @Test fun `HTMLElement resolveMembers has own style`() {
        val members = index.resolveMembers("HTMLElement")
        assertNotNull("HTMLElement.style should be own", members["style"])
        assertEquals(0, members["style"]!!.distance)
    }

    @Test fun `HTMLElement resolveMembers has own tabIndex`() {
        val members = index.resolveMembers("HTMLElement")
        assertEquals(0, members["tabIndex"]!!.distance)
    }

    @Test fun `HTMLElement resolveMembers inherits getAttribute from Element`() {
        val members = index.resolveMembers("HTMLElement")
        val m = members["getAttribute"]
        assertNotNull("HTMLElement should inherit getAttribute", m)
        assertTrue(m!!.distance > 0)
        assertEquals("Element", m.declaringType)
    }

    @Test fun `HTMLElement resolveMembers inherits innerHTML from Element`() {
        val members = index.resolveMembers("HTMLElement")
        val m = members["innerHTML"]
        assertNotNull("HTMLElement should inherit innerHTML", m)
        assertTrue(m!!.distance > 0)
        assertEquals("Element", m.declaringType)
    }

    @Test fun `HTMLElement resolveMembers inherits nodeType from Node`() {
        val members = index.resolveMembers("HTMLElement")
        val m = members["nodeType"]
        assertNotNull("HTMLElement should inherit nodeType", m)
        assertTrue(m!!.distance > 0)
        assertEquals("Node", m.declaringType)
    }

    @Test fun `HTMLElement resolveMembers inherits appendChild from Node`() {
        val members = index.resolveMembers("HTMLElement")
        val m = members["appendChild"]
        assertNotNull("HTMLElement should inherit appendChild", m)
        assertTrue(m!!.distance > 0)
        assertEquals("Node", m.declaringType)
    }

    @Test fun `HTMLElement resolveMembers inherits addEventListener from EventTarget`() {
        val members = index.resolveMembers("HTMLElement")
        val m = members["addEventListener"]
        assertNotNull("HTMLElement should inherit addEventListener from EventTarget", m)
        assertTrue(m!!.distance > 0)
        assertEquals("EventTarget", m.declaringType)
    }

    @Test fun `HTMLElement resolveMembers inherits dispatchEvent from EventTarget`() {
        val members = index.resolveMembers("HTMLElement")
        val m = members["dispatchEvent"]
        assertNotNull("HTMLElement should inherit dispatchEvent from EventTarget", m)
        assertEquals("EventTarget", m!!.declaringType)
    }

    @Test fun `HTMLElement exposes at least 40 members total`() {
        assertTrue(index.resolveMembers("HTMLElement").size >= 40)
    }

    // ─── XMLHttpRequest: own + EventTarget ───────────────────────────────────

    @Test fun `XMLHttpRequest interface has own send`() {
        val members = index.resolveMembers("XMLHttpRequest")
        assertNotNull("XMLHttpRequest.send should be own", members["send"])
        assertEquals(0, members["send"]!!.distance)
    }

    @Test fun `XMLHttpRequest interface has own open`() {
        val members = index.resolveMembers("XMLHttpRequest")
        assertEquals(0, members["open"]!!.distance)
    }

    @Test fun `XMLHttpRequest interface has own readyState`() {
        val members = index.resolveMembers("XMLHttpRequest")
        assertEquals(0, members["readyState"]!!.distance)
    }

    @Test fun `XMLHttpRequest interface inherits addEventListener from EventTarget`() {
        val members = index.resolveMembers("XMLHttpRequest")
        val m = members["addEventListener"]
        assertNotNull("XMLHttpRequest should inherit addEventListener", m)
        assertTrue(m!!.distance > 0)
        assertEquals("EventTarget", m.declaringType)
    }

    @Test fun `XMLHttpRequest interface inherits removeEventListener from EventTarget`() {
        val members = index.resolveMembers("XMLHttpRequest")
        val m = members["removeEventListener"]
        assertNotNull(m)
        assertEquals("EventTarget", m!!.declaringType)
    }

    @Test fun `XMLHttpRequest exposes at least 15 members total`() {
        assertTrue(index.resolveMembers("XMLHttpRequest").size >= 15)
    }

    // ─── WebSocket: own + EventTarget ────────────────────────────────────────

    @Test fun `WebSocket interface has own send`() {
        val members = index.resolveMembers("WebSocket")
        assertNotNull("WebSocket.send should be own", members["send"])
        assertEquals(0, members["send"]!!.distance)
    }

    @Test fun `WebSocket interface has own close`() {
        val members = index.resolveMembers("WebSocket")
        assertEquals(0, members["close"]!!.distance)
    }

    @Test fun `WebSocket interface has own readyState`() {
        val members = index.resolveMembers("WebSocket")
        assertEquals(0, members["readyState"]!!.distance)
    }

    @Test fun `WebSocket interface inherits addEventListener from EventTarget`() {
        val members = index.resolveMembers("WebSocket")
        val m = members["addEventListener"]
        assertNotNull("WebSocket should inherit addEventListener", m)
        assertTrue(m!!.distance > 0)
        assertEquals("EventTarget", m.declaringType)
    }

    @Test fun `WebSocket exposes at least 12 members total`() {
        assertTrue(index.resolveMembers("WebSocket").size >= 12)
    }

    // ─── Worker: inherits from EventTarget ───────────────────────────────────

    @Test fun `Worker interface has own postMessage`() {
        val members = index.resolveMembers("Worker")
        assertNotNull("Worker.postMessage should be own", members["postMessage"])
        assertEquals(0, members["postMessage"]!!.distance)
    }

    @Test fun `Worker interface has own terminate`() {
        val members = index.resolveMembers("Worker")
        assertEquals(0, members["terminate"]!!.distance)
    }

    @Test fun `Worker interface inherits addEventListener from EventTarget`() {
        val members = index.resolveMembers("Worker")
        val m = members["addEventListener"]
        assertNotNull("Worker should inherit addEventListener", m)
        assertTrue(m!!.distance > 0)
        assertEquals("EventTarget", m.declaringType)
    }

    // ─── MessagePort: inherits from EventTarget ───────────────────────────────

    @Test fun `MessagePort interface has own postMessage`() {
        val members = index.resolveMembers("MessagePort")
        assertNotNull(members["postMessage"])
        assertEquals(0, members["postMessage"]!!.distance)
    }

    @Test fun `MessagePort interface inherits addEventListener from EventTarget`() {
        val members = index.resolveMembers("MessagePort")
        val m = members["addEventListener"]
        assertNotNull(m)
        assertEquals("EventTarget", m!!.declaringType)
    }

    // ─── MediaQueryList: own + EventTarget ───────────────────────────────────

    @Test fun `MediaQueryList interface has own matches`() {
        val members = index.resolveMembers("MediaQueryList")
        assertNotNull(members["matches"])
        assertEquals(0, members["matches"]!!.distance)
    }

    @Test fun `MediaQueryList interface inherits addEventListener from EventTarget`() {
        val members = index.resolveMembers("MediaQueryList")
        val m = members["addEventListener"]
        assertNotNull("MediaQueryList should inherit addEventListener", m)
        assertEquals("EventTarget", m!!.declaringType)
    }

    // ─── Inheritance distance ordering ───────────────────────────────────────

    @Test fun `HTMLElement addEventListener distance is greater than Element getAttribute distance`() {
        val members = index.resolveMembers("HTMLElement")
        val addEvt = members["addEventListener"]!!
        val getAttr = members["getAttribute"]!!
        assertTrue(
            "addEventListener (from EventTarget) should be farther than getAttribute (from Element)",
            addEvt.distance > getAttr.distance,
        )
    }

    @Test fun `Document addEventListener distance is greater than nodeType distance`() {
        val members = index.resolveMembers("Document")
        val addEvt = members["addEventListener"]!!
        val nodeType = members["nodeType"]!!
        assertTrue(addEvt.distance > nodeType.distance)
    }

    @Test fun `Document createElement is closer than nodeType`() {
        val members = index.resolveMembers("Document")
        val createElement = members["createElement"]!!
        val nodeType = members["nodeType"]!!
        assertTrue(createElement.distance < nodeType.distance)
    }

    // ─── resolveJsChainType integration ──────────────────────────────────────

    @Test fun `js-window chain type resolves to Window`() {
        assertEquals("Window", index.resolveJsChainType(listOf("window")))
    }

    @Test fun `js-document chain type resolves to Document`() {
        assertEquals("Document", index.resolveJsChainType(listOf("document")))
    }

    @Test fun `js-performance chain type resolves to Performance`() {
        assertEquals("Performance", index.resolveJsChainType(listOf("performance")))
    }

    @Test fun `js-navigator chain type resolves to Navigator`() {
        assertEquals("Navigator", index.resolveJsChainType(listOf("navigator")))
    }

    @Test fun `js-localStorage chain type resolves to Storage`() {
        assertEquals("Storage", index.resolveJsChainType(listOf("localStorage")))
    }

    @Test fun `js-crypto chain type resolves to Crypto`() {
        assertEquals("Crypto", index.resolveJsChainType(listOf("crypto")))
    }

    // ─── Index builder ────────────────────────────────────────────────────────

    companion object {

        private fun fn(returns: String = "void", vararg params: JsParam) =
            JsMember(kind = "method", returns = returns, params = params.toList())

        private fun prop(type: String) = JsMember(kind = "property", type = type)

        private fun iface(vararg members: Pair<String, JsMember>, extends: List<String> = emptyList()) =
            JsInterface(extends = extends, members = members.associate { (k, v) -> k to listOf(v) })

        private fun v(type: String) = JsVariableInfo(type = type)

        /**
         * Builds an index that faithfully models the TypeScript lib.dom.d.ts inheritance tree.
         * Every interface that is an EventTarget sub-type declares `extends = listOf("EventTarget")`
         * so that [JsSymbolIndex.resolveMembers] walks the chain correctly.
         */
        fun buildCompletenessIndex(): JsSymbolIndex {
            val index = JsSymbolIndex()

            val ifaceMap = mapOf(
                // ── Root of the event system ──────────────────────────────────────
                "EventTarget" to iface(
                    "addEventListener" to fn("void",
                        JsParam("type", "string"), JsParam("listener", "Function")),
                    "removeEventListener" to fn("void",
                        JsParam("type", "string"), JsParam("listener", "Function")),
                    "dispatchEvent" to fn("boolean", JsParam("event", "Event")),
                ),

                // ── Mixin inherited by Window ─────────────────────────────────────
                "WindowOrWorkerGlobalScope" to iface(
                    "setTimeout" to fn("number",
                        JsParam("handler", "TimerHandler"),
                        JsParam("timeout", "number", optional = true)),
                    "clearTimeout" to fn("void", JsParam("id", "number", optional = true)),
                    "setInterval" to fn("number",
                        JsParam("handler", "TimerHandler"),
                        JsParam("timeout", "number", optional = true)),
                    "clearInterval" to fn("void", JsParam("id", "number", optional = true)),
                    "fetch" to fn("Promise",
                        JsParam("input", "RequestInfo"),
                        JsParam("init", "RequestInit", optional = true)),
                    "atob" to fn("string", JsParam("data", "string")),
                    "btoa" to fn("string", JsParam("data", "string")),
                    "queueMicrotask" to fn("void", JsParam("callback", "Function")),
                    "structuredClone" to fn("any", JsParam("value", "any")),
                    "createImageBitmap" to fn("Promise", JsParam("image", "ImageBitmapSource")),
                ),

                // ── Window: own props + two inherited mixins ──────────────────────
                "Window" to iface(
                    "innerWidth" to prop("number"),
                    "innerHeight" to prop("number"),
                    "outerWidth" to prop("number"),
                    "outerHeight" to prop("number"),
                    "scrollX" to prop("number"),
                    "scrollY" to prop("number"),
                    "pageXOffset" to prop("number"),
                    "pageYOffset" to prop("number"),
                    "devicePixelRatio" to prop("number"),
                    "name" to prop("string"),
                    "status" to prop("string"),
                    "closed" to prop("boolean"),
                    "screenLeft" to prop("number"),
                    "screenTop" to prop("number"),
                    "screenX" to prop("number"),
                    "screenY" to prop("number"),
                    extends = listOf("EventTarget", "WindowOrWorkerGlobalScope"),
                ),

                // ── Node: base for all DOM nodes ──────────────────────────────────
                "Node" to iface(
                    "nodeType" to prop("number"),
                    "nodeName" to prop("string"),
                    "nodeValue" to prop("string"),
                    "textContent" to prop("string"),
                    "parentNode" to prop("Node"),
                    "parentElement" to prop("Element"),
                    "childNodes" to prop("NodeList"),
                    "firstChild" to prop("Node"),
                    "lastChild" to prop("Node"),
                    "nextSibling" to prop("Node"),
                    "previousSibling" to prop("Node"),
                    "ownerDocument" to prop("Document"),
                    "isConnected" to prop("boolean"),
                    "cloneNode" to fn("Node", JsParam("deep", "boolean", optional = true)),
                    "appendChild" to fn("Node", JsParam("node", "Node")),
                    "insertBefore" to fn("Node",
                        JsParam("node", "Node"), JsParam("child", "Node")),
                    "removeChild" to fn("Node", JsParam("child", "Node")),
                    "replaceChild" to fn("Node",
                        JsParam("node", "Node"), JsParam("child", "Node")),
                    "contains" to fn("boolean", JsParam("other", "Node")),
                    "hasChildNodes" to fn("boolean"),
                    "normalize" to fn("void"),
                    "getRootNode" to fn("Node"),
                    extends = listOf("EventTarget"),
                ),

                // ── Document: extends Node (→ EventTarget) ────────────────────────
                "Document" to iface(
                    "createElement" to fn("Element", JsParam("tagName", "string")),
                    "createElementNS" to fn("Element",
                        JsParam("namespace", "string"), JsParam("qualifiedName", "string")),
                    "getElementById" to fn("Element", JsParam("id", "string")),
                    "querySelector" to fn("Element", JsParam("selectors", "string")),
                    "querySelectorAll" to fn("NodeList", JsParam("selectors", "string")),
                    "createTextNode" to fn("Text", JsParam("data", "string")),
                    "createComment" to fn("Comment", JsParam("data", "string")),
                    "createDocumentFragment" to fn("DocumentFragment"),
                    "createRange" to fn("Range"),
                    "createTreeWalker" to fn("TreeWalker", JsParam("root", "Node")),
                    "adoptNode" to fn("Node", JsParam("node", "Node")),
                    "importNode" to fn("Node",
                        JsParam("node", "Node"), JsParam("deep", "boolean", optional = true)),
                    "hasFocus" to fn("boolean"),
                    "elementFromPoint" to fn("Element",
                        JsParam("x", "number"), JsParam("y", "number")),
                    "elementsFromPoint" to fn("Array",
                        JsParam("x", "number"), JsParam("y", "number")),
                    "getSelection" to fn("Selection"),
                    "title" to prop("string"),
                    "body" to prop("HTMLElement"),
                    "head" to prop("HTMLElement"),
                    "cookie" to prop("string"),
                    "URL" to prop("string"),
                    "readyState" to prop("string"),
                    "characterSet" to prop("string"),
                    "visibilityState" to prop("string"),
                    "hidden" to prop("boolean"),
                    "referrer" to prop("string"),
                    "domain" to prop("string"),
                    "lastModified" to prop("string"),
                    "documentElement" to prop("HTMLElement"),
                    "designMode" to prop("string"),
                    extends = listOf("Node"),
                ),

                // ── Element: extends Node (→ EventTarget) ─────────────────────────
                "Element" to iface(
                    "tagName" to prop("string"),
                    "id" to prop("string"),
                    "className" to prop("string"),
                    "classList" to prop("DOMTokenList"),
                    "innerHTML" to prop("string"),
                    "outerHTML" to prop("string"),
                    "scrollTop" to prop("number"),
                    "scrollLeft" to prop("number"),
                    "scrollWidth" to prop("number"),
                    "scrollHeight" to prop("number"),
                    "clientTop" to prop("number"),
                    "clientLeft" to prop("number"),
                    "clientWidth" to prop("number"),
                    "clientHeight" to prop("number"),
                    "namespaceURI" to prop("string"),
                    "localName" to prop("string"),
                    "getAttribute" to fn("string", JsParam("qualifiedName", "string")),
                    "setAttribute" to fn("void",
                        JsParam("qualifiedName", "string"), JsParam("value", "string")),
                    "hasAttribute" to fn("boolean", JsParam("qualifiedName", "string")),
                    "removeAttribute" to fn("void", JsParam("qualifiedName", "string")),
                    "toggleAttribute" to fn("boolean",
                        JsParam("qualifiedName", "string"),
                        JsParam("force", "boolean", optional = true)),
                    "getBoundingClientRect" to fn("DOMRect"),
                    "getClientRects" to fn("DOMRectList"),
                    "matches" to fn("boolean", JsParam("selectors", "string")),
                    "closest" to fn("Element", JsParam("selectors", "string")),
                    "querySelector" to fn("Element", JsParam("selectors", "string")),
                    "querySelectorAll" to fn("NodeList", JsParam("selectors", "string")),
                    "append" to fn("void"),
                    "prepend" to fn("void"),
                    "after" to fn("void"),
                    "before" to fn("void"),
                    "remove" to fn("void"),
                    "replaceWith" to fn("void"),
                    "insertAdjacentElement" to fn("Element"),
                    "insertAdjacentHTML" to fn("void"),
                    "insertAdjacentText" to fn("void"),
                    "scrollIntoView" to fn("void"),
                    "requestFullscreen" to fn("Promise"),
                    extends = listOf("Node"),
                ),

                // ── HTMLElement: extends Element (→ Node → EventTarget) ───────────
                "HTMLElement" to iface(
                    "style" to prop("CSSStyleDeclaration"),
                    "dataset" to prop("DOMStringMap"),
                    "tabIndex" to prop("number"),
                    "hidden" to prop("boolean"),
                    "offsetWidth" to prop("number"),
                    "offsetHeight" to prop("number"),
                    "offsetTop" to prop("number"),
                    "offsetLeft" to prop("number"),
                    "offsetParent" to prop("Element"),
                    "draggable" to prop("boolean"),
                    "contentEditable" to prop("string"),
                    "isContentEditable" to prop("boolean"),
                    "lang" to prop("string"),
                    "dir" to prop("string"),
                    "title" to prop("string"),
                    "accessKey" to prop("string"),
                    "spellcheck" to prop("boolean"),
                    "translate" to prop("boolean"),
                    "innerText" to prop("string"),
                    "outerText" to prop("string"),
                    "click" to fn("void"),
                    "focus" to fn("void"),
                    "blur" to fn("void"),
                    "attachInternals" to fn("ElementInternals"),
                    extends = listOf("Element"),
                ),

                // ── Performance: own + EventTarget ────────────────────────────────
                "Performance" to iface(
                    "now" to fn("number"),
                    "mark" to fn("PerformanceMark", JsParam("markName", "string")),
                    "measure" to fn("PerformanceMeasure", JsParam("measureName", "string")),
                    "getEntries" to fn("PerformanceEntryList"),
                    "getEntriesByName" to fn("PerformanceEntryList", JsParam("name", "string")),
                    "getEntriesByType" to fn("PerformanceEntryList", JsParam("type", "string")),
                    "clearMarks" to fn("void"),
                    "clearMeasures" to fn("void"),
                    "clearResourceTimings" to fn("void"),
                    "setResourceTimingBufferSize" to fn("void", JsParam("maxSize", "number")),
                    "toJSON" to fn("any"),
                    "timeOrigin" to prop("number"),
                    extends = listOf("EventTarget"),
                ),

                // ── XMLHttpRequest: own + EventTarget ─────────────────────────────
                "XMLHttpRequest" to iface(
                    "readyState" to prop("number"),
                    "status" to prop("number"),
                    "statusText" to prop("string"),
                    "response" to prop("any"),
                    "responseText" to prop("string"),
                    "responseXML" to prop("Document"),
                    "responseType" to prop("string"),
                    "responseURL" to prop("string"),
                    "timeout" to prop("number"),
                    "withCredentials" to prop("boolean"),
                    "upload" to prop("XMLHttpRequestUpload"),
                    "UNSENT" to prop("number"),
                    "OPENED" to prop("number"),
                    "HEADERS_RECEIVED" to prop("number"),
                    "LOADING" to prop("number"),
                    "DONE" to prop("number"),
                    "open" to fn("void", JsParam("method", "string"), JsParam("url", "string")),
                    "send" to fn("void", JsParam("body", "Document", optional = true)),
                    "abort" to fn("void"),
                    "getResponseHeader" to fn("string", JsParam("name", "string")),
                    "getAllResponseHeaders" to fn("string"),
                    "setRequestHeader" to fn("void",
                        JsParam("name", "string"), JsParam("value", "string")),
                    "overrideMimeType" to fn("void", JsParam("mime", "string")),
                    extends = listOf("EventTarget"),
                ),

                // ── WebSocket: own + EventTarget ──────────────────────────────────
                "WebSocket" to iface(
                    "readyState" to prop("number"),
                    "url" to prop("string"),
                    "protocol" to prop("string"),
                    "extensions" to prop("string"),
                    "bufferedAmount" to prop("number"),
                    "binaryType" to prop("string"),
                    "CONNECTING" to prop("number"),
                    "OPEN" to prop("number"),
                    "CLOSING" to prop("number"),
                    "CLOSED" to prop("number"),
                    "send" to fn("void", JsParam("data", "string")),
                    "close" to fn("void",
                        JsParam("code", "number", optional = true),
                        JsParam("reason", "string", optional = true)),
                    extends = listOf("EventTarget"),
                ),

                // ── Worker: own + EventTarget ─────────────────────────────────────
                "Worker" to iface(
                    "postMessage" to fn("void", JsParam("message", "any")),
                    "terminate" to fn("void"),
                    extends = listOf("EventTarget"),
                ),

                // ── MessagePort: own + EventTarget ────────────────────────────────
                "MessagePort" to iface(
                    "postMessage" to fn("void", JsParam("message", "any")),
                    "start" to fn("void"),
                    "close" to fn("void"),
                    extends = listOf("EventTarget"),
                ),

                // ── MediaQueryList: own + EventTarget ─────────────────────────────
                "MediaQueryList" to iface(
                    "matches" to prop("boolean"),
                    "media" to prop("string"),
                    "addListener" to fn("void"),
                    "removeListener" to fn("void"),
                    extends = listOf("EventTarget"),
                ),

                // ── Standalone interfaces ─────────────────────────────────────────
                "Navigator" to iface(
                    "userAgent" to prop("string"),
                    "language" to prop("string"),
                    "languages" to prop("ReadonlyArray"),
                    "platform" to prop("string"),
                    "cookieEnabled" to prop("boolean"),
                    "onLine" to prop("boolean"),
                    "hardwareConcurrency" to prop("number"),
                    "maxTouchPoints" to prop("number"),
                    "vendor" to prop("string"),
                    "doNotTrack" to prop("string"),
                    "deviceMemory" to prop("number"),
                    "geolocation" to prop("Geolocation"),
                    "mediaDevices" to prop("MediaDevices"),
                    "sendBeacon" to fn("boolean", JsParam("url", "string")),
                    "vibrate" to fn("boolean", JsParam("pattern", "number")),
                    "share" to fn("Promise"),
                    "registerProtocolHandler" to fn("void"),
                    "requestMediaKeySystemAccess" to fn("Promise"),
                ),

                "Location" to iface(
                    "href" to prop("string"),
                    "protocol" to prop("string"),
                    "host" to prop("string"),
                    "hostname" to prop("string"),
                    "port" to prop("string"),
                    "pathname" to prop("string"),
                    "search" to prop("string"),
                    "hash" to prop("string"),
                    "origin" to prop("string"),
                    "assign" to fn("void", JsParam("url", "string")),
                    "replace" to fn("void", JsParam("url", "string")),
                    "reload" to fn("void"),
                    "toString" to fn("string"),
                ),

                "History" to iface(
                    "length" to prop("number"),
                    "state" to prop("any"),
                    "scrollRestoration" to prop("string"),
                    "pushState" to fn("void",
                        JsParam("data", "any"), JsParam("unused", "string")),
                    "replaceState" to fn("void",
                        JsParam("data", "any"), JsParam("unused", "string")),
                    "go" to fn("void", JsParam("delta", "number", optional = true)),
                    "back" to fn("void"),
                    "forward" to fn("void"),
                ),

                "Screen" to iface(
                    "width" to prop("number"),
                    "height" to prop("number"),
                    "availWidth" to prop("number"),
                    "availHeight" to prop("number"),
                    "colorDepth" to prop("number"),
                    "pixelDepth" to prop("number"),
                    "orientation" to prop("ScreenOrientation"),
                ),

                "Console" to iface(
                    "log" to fn("void"),
                    "warn" to fn("void"),
                    "error" to fn("void"),
                    "info" to fn("void"),
                    "debug" to fn("void"),
                    "trace" to fn("void"),
                    "dir" to fn("void"),
                    "dirxml" to fn("void"),
                    "table" to fn("void"),
                    "group" to fn("void"),
                    "groupEnd" to fn("void"),
                    "groupCollapsed" to fn("void"),
                    "time" to fn("void", JsParam("label", "string", optional = true)),
                    "timeEnd" to fn("void", JsParam("label", "string", optional = true)),
                    "timeLog" to fn("void"),
                    "timeStamp" to fn("void"),
                    "count" to fn("void"),
                    "countReset" to fn("void"),
                    "assert" to fn("void"),
                    "clear" to fn("void"),
                    "profile" to fn("void"),
                    "profileEnd" to fn("void"),
                ),

                "Storage" to iface(
                    "length" to prop("number"),
                    "key" to fn("string", JsParam("index", "number")),
                    "getItem" to fn("string", JsParam("key", "string")),
                    "setItem" to fn("void",
                        JsParam("key", "string"), JsParam("value", "string")),
                    "removeItem" to fn("void", JsParam("key", "string")),
                    "clear" to fn("void"),
                ),

                "Crypto" to iface(
                    "subtle" to prop("SubtleCrypto"),
                    "getRandomValues" to fn("ArrayBufferView",
                        JsParam("array", "ArrayBufferView")),
                    "randomUUID" to fn("string"),
                ),

                "IDBFactory" to iface(
                    "open" to fn("IDBOpenDBRequest", JsParam("name", "string")),
                    "deleteDatabase" to fn("IDBOpenDBRequest", JsParam("name", "string")),
                    "cmp" to fn("number"),
                    "databases" to fn("Promise"),
                ),
            )

            // Globals — using real TypeScript type names (no TYPE$ for instances/singletons)
            val varMap = mapOf(
                // Instance globals
                "window" to v("Window"),
                "self" to v("Window"),
                "document" to v("Document"),
                "navigator" to v("Navigator"),
                "location" to v("Location"),
                "history" to v("History"),
                "screen" to v("Screen"),
                "console" to v("Console"),
                "performance" to v("Performance"),
                "crypto" to v("Crypto"),
                "localStorage" to v("Storage"),
                "sessionStorage" to v("Storage"),
                "indexedDB" to v("IDBFactory"),
                // Constructor globals (TYPE$ = typeof companion)
                "EventTarget" to v("TYPE\$EventTarget"),
                "XMLHttpRequest" to v("TYPE\$XMLHttpRequest"),
                "WebSocket" to v("TYPE\$WebSocket"),
                "Worker" to v("TYPE\$Worker"),
                "MessagePort" to v("TYPE\$MessagePort"),
                "MediaQueryList" to v("TYPE\$MediaQueryList"),
                // Dummy global to allow assertOwn("EventTarget_iface", ...) through resolveMembers directly
                "EventTarget_iface" to v("EventTarget"),
            )

            index.load(ParsedSymbols(interfaces = ifaceMap, variables = varMap, functions = emptyMap()))
            index.setLoaded(true)
            return index
        }
    }
}
