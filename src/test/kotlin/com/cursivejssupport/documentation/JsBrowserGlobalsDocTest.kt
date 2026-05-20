package com.cursivejssupport.documentation

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsParam
import com.cursivejssupport.parser.JsVariableInfo
import com.cursivejssupport.parser.ParsedSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive doc/hover coverage for all js/<global> symbol categories available when
 * shadow-cljs targets the browser. Tests [InteropDocResolver.resolveFromParts] for every
 * major category: ECMAScript primitives, window globals, browser functions, constructor
 * globals, chain resolution, member access, inheritance, and typeof-companion sanitization.
 */
class JsBrowserGlobalsDocTest {

    private lateinit var index: JsSymbolIndex

    @Before
    fun setUp() {
        index = buildBrowserIndex()
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun resolve(namespace: String?, name: String, receiverType: String? = null) =
        InteropDocResolver.resolveFromParts(
            namespace = namespace,
            name = name,
            receiverType = receiverType,
            aliases = emptyMap(),
            index = index,
        )

    private fun jsGlobal(name: String) = resolve("js", name)
    private fun jsDot(name: String) = resolve("js", name)

    // ─── ECMAScript primitive globals ───────────────────────────────────────────

    @Test fun `js-undefined resolves to JsGlobal`() {
        val s = jsGlobal("undefined") as InteropDocSubject.JsGlobal
        assertEquals("undefined", s.name)
        assertEquals("undefined", s.info.type)
    }

    @Test fun `js-Infinity resolves to JsGlobal`() {
        val s = jsGlobal("Infinity") as InteropDocSubject.JsGlobal
        assertEquals("number", s.info.type)
    }

    @Test fun `js-NaN resolves to JsGlobal`() {
        val s = jsGlobal("NaN") as InteropDocSubject.JsGlobal
        assertEquals("number", s.info.type)
    }

    @Test fun `js-globalThis resolves to JsGlobal`() {
        val s = jsGlobal("globalThis") as InteropDocSubject.JsGlobal
        assertEquals("Window", s.info.type)
    }

    // ─── ECMAScript global functions ────────────────────────────────────────────

    @Test fun `js-eval resolves to JsFunction`() {
        val s = jsGlobal("eval") as InteropDocSubject.JsFunction
        assertEquals("eval", s.name)
    }

    @Test fun `js-isFinite resolves to JsFunction`() {
        assertTrue(jsGlobal("isFinite") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-isNaN resolves to JsFunction`() {
        assertTrue(jsGlobal("isNaN") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-parseFloat resolves to JsFunction`() {
        val s = jsGlobal("parseFloat") as InteropDocSubject.JsFunction
        assertEquals("number", s.overload.returns)
    }

    @Test fun `js-parseInt resolves to JsFunction`() {
        val s = jsGlobal("parseInt") as InteropDocSubject.JsFunction
        assertEquals("number", s.overload.returns)
    }

    @Test fun `js-decodeURI resolves to JsFunction`() {
        assertTrue(jsGlobal("decodeURI") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-decodeURIComponent resolves to JsFunction`() {
        assertTrue(jsGlobal("decodeURIComponent") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-encodeURI resolves to JsFunction`() {
        assertTrue(jsGlobal("encodeURI") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-encodeURIComponent resolves to JsFunction`() {
        assertTrue(jsGlobal("encodeURIComponent") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-structuredClone resolves to JsFunction`() {
        assertTrue(jsGlobal("structuredClone") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-queueMicrotask resolves to JsFunction`() {
        assertTrue(jsGlobal("queueMicrotask") is InteropDocSubject.JsFunction)
    }

    // ─── ECMAScript constructor globals (variables) ─────────────────────────────

    @Test fun `js-Object resolves to JsGlobal`() {
        assertTrue(jsGlobal("Object") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Function resolves to JsGlobal`() {
        assertTrue(jsGlobal("Function") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Boolean resolves to JsGlobal`() {
        assertTrue(jsGlobal("Boolean") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Symbol resolves to JsGlobal`() {
        assertTrue(jsGlobal("Symbol") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-BigInt resolves to JsGlobal`() {
        assertTrue(jsGlobal("BigInt") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Number resolves to JsGlobal`() {
        assertTrue(jsGlobal("Number") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Math resolves to JsGlobal`() {
        val s = jsGlobal("Math") as InteropDocSubject.JsGlobal
        assertEquals("Math", s.info.type)
    }

    @Test fun `js-Date resolves to JsGlobal`() {
        assertTrue(jsGlobal("Date") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-String resolves to JsGlobal`() {
        assertTrue(jsGlobal("String") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-RegExp resolves to JsGlobal`() {
        assertTrue(jsGlobal("RegExp") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Array resolves to JsGlobal`() {
        assertTrue(jsGlobal("Array") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Map resolves to JsGlobal`() {
        assertTrue(jsGlobal("Map") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Set resolves to JsGlobal`() {
        assertTrue(jsGlobal("Set") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-WeakMap resolves to JsGlobal`() {
        assertTrue(jsGlobal("WeakMap") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-WeakSet resolves to JsGlobal`() {
        assertTrue(jsGlobal("WeakSet") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-WeakRef resolves to JsGlobal`() {
        assertTrue(jsGlobal("WeakRef") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-FinalizationRegistry resolves to JsGlobal`() {
        assertTrue(jsGlobal("FinalizationRegistry") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Promise resolves to JsGlobal`() {
        assertTrue(jsGlobal("Promise") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Proxy resolves to JsGlobal`() {
        assertTrue(jsGlobal("Proxy") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-Reflect resolves to JsGlobal`() {
        val s = jsGlobal("Reflect") as InteropDocSubject.JsGlobal
        assertEquals("Reflect", s.info.type)
    }

    @Test fun `js-JSON resolves to JsGlobal`() {
        val s = jsGlobal("JSON") as InteropDocSubject.JsGlobal
        assertEquals("JSON", s.info.type)
    }

    @Test fun `js-Atomics resolves to JsGlobal`() {
        val s = jsGlobal("Atomics") as InteropDocSubject.JsGlobal
        assertEquals("Atomics", s.info.type)
    }

    // ─── Structured data ────────────────────────────────────────────────────────

    @Test fun `js-ArrayBuffer resolves to JsGlobal`() {
        assertTrue(jsGlobal("ArrayBuffer") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-SharedArrayBuffer resolves to JsGlobal`() {
        assertTrue(jsGlobal("SharedArrayBuffer") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-DataView resolves to JsGlobal`() {
        assertTrue(jsGlobal("DataView") is InteropDocSubject.JsGlobal)
    }

    // ─── Typed arrays ────────────────────────────────────────────────────────────

    @Test fun `js-Int8Array resolves to JsGlobal`() { assertTrue(jsGlobal("Int8Array") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Uint8Array resolves to JsGlobal`() { assertTrue(jsGlobal("Uint8Array") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Uint8ClampedArray resolves to JsGlobal`() { assertTrue(jsGlobal("Uint8ClampedArray") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Int16Array resolves to JsGlobal`() { assertTrue(jsGlobal("Int16Array") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Uint16Array resolves to JsGlobal`() { assertTrue(jsGlobal("Uint16Array") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Int32Array resolves to JsGlobal`() { assertTrue(jsGlobal("Int32Array") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Uint32Array resolves to JsGlobal`() { assertTrue(jsGlobal("Uint32Array") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Float32Array resolves to JsGlobal`() { assertTrue(jsGlobal("Float32Array") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Float64Array resolves to JsGlobal`() { assertTrue(jsGlobal("Float64Array") is InteropDocSubject.JsGlobal) }
    @Test fun `js-BigInt64Array resolves to JsGlobal`() { assertTrue(jsGlobal("BigInt64Array") is InteropDocSubject.JsGlobal) }
    @Test fun `js-BigUint64Array resolves to JsGlobal`() { assertTrue(jsGlobal("BigUint64Array") is InteropDocSubject.JsGlobal) }

    // ─── Error types ─────────────────────────────────────────────────────────────

    @Test fun `js-Error resolves to JsGlobal`() { assertTrue(jsGlobal("Error") is InteropDocSubject.JsGlobal) }
    @Test fun `js-AggregateError resolves to JsGlobal`() { assertTrue(jsGlobal("AggregateError") is InteropDocSubject.JsGlobal) }
    @Test fun `js-EvalError resolves to JsGlobal`() { assertTrue(jsGlobal("EvalError") is InteropDocSubject.JsGlobal) }
    @Test fun `js-RangeError resolves to JsGlobal`() { assertTrue(jsGlobal("RangeError") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ReferenceError resolves to JsGlobal`() { assertTrue(jsGlobal("ReferenceError") is InteropDocSubject.JsGlobal) }
    @Test fun `js-SyntaxError resolves to JsGlobal`() { assertTrue(jsGlobal("SyntaxError") is InteropDocSubject.JsGlobal) }
    @Test fun `js-TypeError resolves to JsGlobal`() { assertTrue(jsGlobal("TypeError") is InteropDocSubject.JsGlobal) }
    @Test fun `js-URIError resolves to JsGlobal`() { assertTrue(jsGlobal("URIError") is InteropDocSubject.JsGlobal) }

    // ─── Window / frame globals ──────────────────────────────────────────────────

    @Test fun `js-window resolves to JsGlobal with type Window`() {
        val s = jsGlobal("window") as InteropDocSubject.JsGlobal
        assertEquals("Window", s.info.type)
    }

    @Test fun `js-self resolves to JsGlobal with type Window`() {
        val s = jsGlobal("self") as InteropDocSubject.JsGlobal
        assertEquals("Window", s.info.type)
    }

    @Test fun `js-top resolves to JsGlobal`() {
        assertTrue(jsGlobal("top") is InteropDocSubject.JsGlobal)
    }

    @Test fun `js-parent resolves to JsGlobal`() {
        assertTrue(jsGlobal("parent") is InteropDocSubject.JsGlobal)
    }

    // ─── DOM globals ────────────────────────────────────────────────────────────

    @Test fun `js-document resolves to JsGlobal with type Document`() {
        val s = jsGlobal("document") as InteropDocSubject.JsGlobal
        assertEquals("document", s.name)
        assertEquals("Document", s.info.type)
    }

    @Test fun `js-navigator resolves to JsGlobal with type Navigator`() {
        val s = jsGlobal("navigator") as InteropDocSubject.JsGlobal
        assertEquals("Navigator", s.info.type)
    }

    @Test fun `js-location resolves to JsGlobal with type Location`() {
        val s = jsGlobal("location") as InteropDocSubject.JsGlobal
        assertEquals("Location", s.info.type)
    }

    @Test fun `js-history resolves to JsGlobal with type History`() {
        val s = jsGlobal("history") as InteropDocSubject.JsGlobal
        assertEquals("History", s.info.type)
    }

    @Test fun `js-screen resolves to JsGlobal with type Screen`() {
        val s = jsGlobal("screen") as InteropDocSubject.JsGlobal
        assertEquals("Screen", s.info.type)
    }

    @Test fun `js-console resolves to JsGlobal with type Console`() {
        val s = jsGlobal("console") as InteropDocSubject.JsGlobal
        assertEquals("Console", s.info.type)
    }

    @Test fun `js-performance resolves to JsGlobal with type Performance`() {
        val s = jsGlobal("performance") as InteropDocSubject.JsGlobal
        assertEquals("Performance", s.info.type)
    }

    @Test fun `js-crypto resolves to JsGlobal with type Crypto`() {
        val s = jsGlobal("crypto") as InteropDocSubject.JsGlobal
        assertEquals("Crypto", s.info.type)
    }

    // ─── Storage globals ─────────────────────────────────────────────────────────

    @Test fun `js-localStorage resolves to JsGlobal with type Storage`() {
        val s = jsGlobal("localStorage") as InteropDocSubject.JsGlobal
        assertEquals("Storage", s.info.type)
    }

    @Test fun `js-sessionStorage resolves to JsGlobal with type Storage`() {
        val s = jsGlobal("sessionStorage") as InteropDocSubject.JsGlobal
        assertEquals("Storage", s.info.type)
    }

    @Test fun `js-indexedDB resolves to JsGlobal with type IDBFactory`() {
        val s = jsGlobal("indexedDB") as InteropDocSubject.JsGlobal
        assertEquals("IDBFactory", s.info.type)
    }

    @Test fun `js-caches resolves to JsGlobal with type CacheStorage`() {
        val s = jsGlobal("caches") as InteropDocSubject.JsGlobal
        assertEquals("CacheStorage", s.info.type)
    }

    // ─── Browser window functions ─────────────────────────────────────────────

    @Test fun `js-fetch resolves to JsFunction`() {
        val s = jsGlobal("fetch") as InteropDocSubject.JsFunction
        assertEquals("fetch", s.name)
        assertEquals("Promise", s.overload.returns)
    }

    @Test fun `js-alert resolves to JsFunction`() {
        assertTrue(jsGlobal("alert") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-confirm resolves to JsFunction`() {
        val s = jsGlobal("confirm") as InteropDocSubject.JsFunction
        assertEquals("boolean", s.overload.returns)
    }

    @Test fun `js-prompt resolves to JsFunction`() {
        val s = jsGlobal("prompt") as InteropDocSubject.JsFunction
        assertEquals("string", s.overload.returns)
    }

    @Test fun `js-open resolves to JsFunction`() {
        assertTrue(jsGlobal("open") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-close resolves to JsFunction`() {
        assertTrue(jsGlobal("close") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-focus resolves to JsFunction`() {
        assertTrue(jsGlobal("focus") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-blur resolves to JsFunction`() {
        assertTrue(jsGlobal("blur") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-print resolves to JsFunction`() {
        assertTrue(jsGlobal("print") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-setTimeout resolves to JsFunction`() {
        val s = jsGlobal("setTimeout") as InteropDocSubject.JsFunction
        assertEquals("number", s.overload.returns)
    }

    @Test fun `js-clearTimeout resolves to JsFunction`() {
        assertTrue(jsGlobal("clearTimeout") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-setInterval resolves to JsFunction`() {
        val s = jsGlobal("setInterval") as InteropDocSubject.JsFunction
        assertEquals("number", s.overload.returns)
    }

    @Test fun `js-clearInterval resolves to JsFunction`() {
        assertTrue(jsGlobal("clearInterval") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-requestAnimationFrame resolves to JsFunction`() {
        val s = jsGlobal("requestAnimationFrame") as InteropDocSubject.JsFunction
        assertEquals("number", s.overload.returns)
    }

    @Test fun `js-cancelAnimationFrame resolves to JsFunction`() {
        assertTrue(jsGlobal("cancelAnimationFrame") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-requestIdleCallback resolves to JsFunction`() {
        assertTrue(jsGlobal("requestIdleCallback") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-cancelIdleCallback resolves to JsFunction`() {
        assertTrue(jsGlobal("cancelIdleCallback") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-getComputedStyle resolves to JsFunction`() {
        val s = jsGlobal("getComputedStyle") as InteropDocSubject.JsFunction
        assertEquals("CSSStyleDeclaration", s.overload.returns)
    }

    @Test fun `js-matchMedia resolves to JsFunction`() {
        val s = jsGlobal("matchMedia") as InteropDocSubject.JsFunction
        assertEquals("MediaQueryList", s.overload.returns)
    }

    @Test fun `js-getSelection resolves to JsFunction`() {
        assertTrue(jsGlobal("getSelection") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-postMessage resolves to JsFunction`() {
        assertTrue(jsGlobal("postMessage") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-atob resolves to JsFunction`() {
        val s = jsGlobal("atob") as InteropDocSubject.JsFunction
        assertEquals("string", s.overload.returns)
    }

    @Test fun `js-btoa resolves to JsFunction`() {
        val s = jsGlobal("btoa") as InteropDocSubject.JsFunction
        assertEquals("string", s.overload.returns)
    }

    @Test fun `js-createImageBitmap resolves to JsFunction`() {
        assertTrue(jsGlobal("createImageBitmap") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-scroll resolves to JsFunction`() {
        assertTrue(jsGlobal("scroll") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-scrollTo resolves to JsFunction`() {
        assertTrue(jsGlobal("scrollTo") is InteropDocSubject.JsFunction)
    }

    @Test fun `js-scrollBy resolves to JsFunction`() {
        assertTrue(jsGlobal("scrollBy") is InteropDocSubject.JsFunction)
    }

    // ─── Web API constructor globals ─────────────────────────────────────────────

    @Test fun `js-URL resolves to JsGlobal`() { assertTrue(jsGlobal("URL") is InteropDocSubject.JsGlobal) }
    @Test fun `js-URLSearchParams resolves to JsGlobal`() { assertTrue(jsGlobal("URLSearchParams") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Blob resolves to JsGlobal`() { assertTrue(jsGlobal("Blob") is InteropDocSubject.JsGlobal) }
    @Test fun `js-File resolves to JsGlobal`() { assertTrue(jsGlobal("File") is InteropDocSubject.JsGlobal) }
    @Test fun `js-FileList resolves to JsGlobal`() { assertTrue(jsGlobal("FileList") is InteropDocSubject.JsGlobal) }
    @Test fun `js-FileReader resolves to JsGlobal`() { assertTrue(jsGlobal("FileReader") is InteropDocSubject.JsGlobal) }
    @Test fun `js-FormData resolves to JsGlobal`() { assertTrue(jsGlobal("FormData") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Headers resolves to JsGlobal`() { assertTrue(jsGlobal("Headers") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Request resolves to JsGlobal`() { assertTrue(jsGlobal("Request") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Response resolves to JsGlobal`() { assertTrue(jsGlobal("Response") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ReadableStream resolves to JsGlobal`() { assertTrue(jsGlobal("ReadableStream") is InteropDocSubject.JsGlobal) }
    @Test fun `js-WritableStream resolves to JsGlobal`() { assertTrue(jsGlobal("WritableStream") is InteropDocSubject.JsGlobal) }
    @Test fun `js-TransformStream resolves to JsGlobal`() { assertTrue(jsGlobal("TransformStream") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ByteLengthQueuingStrategy resolves to JsGlobal`() { assertTrue(jsGlobal("ByteLengthQueuingStrategy") is InteropDocSubject.JsGlobal) }
    @Test fun `js-CountQueuingStrategy resolves to JsGlobal`() { assertTrue(jsGlobal("CountQueuingStrategy") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Event resolves to JsGlobal`() { assertTrue(jsGlobal("Event") is InteropDocSubject.JsGlobal) }
    @Test fun `js-EventTarget resolves to JsGlobal`() { assertTrue(jsGlobal("EventTarget") is InteropDocSubject.JsGlobal) }
    @Test fun `js-CustomEvent resolves to JsGlobal`() { assertTrue(jsGlobal("CustomEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-AbortController resolves to JsGlobal`() { assertTrue(jsGlobal("AbortController") is InteropDocSubject.JsGlobal) }
    @Test fun `js-AbortSignal resolves to JsGlobal`() { assertTrue(jsGlobal("AbortSignal") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Worker resolves to JsGlobal`() { assertTrue(jsGlobal("Worker") is InteropDocSubject.JsGlobal) }
    @Test fun `js-SharedWorker resolves to JsGlobal`() { assertTrue(jsGlobal("SharedWorker") is InteropDocSubject.JsGlobal) }
    @Test fun `js-MessageChannel resolves to JsGlobal`() { assertTrue(jsGlobal("MessageChannel") is InteropDocSubject.JsGlobal) }
    @Test fun `js-MessagePort resolves to JsGlobal`() { assertTrue(jsGlobal("MessagePort") is InteropDocSubject.JsGlobal) }
    @Test fun `js-BroadcastChannel resolves to JsGlobal`() { assertTrue(jsGlobal("BroadcastChannel") is InteropDocSubject.JsGlobal) }
    @Test fun `js-XMLHttpRequest resolves to JsGlobal`() { assertTrue(jsGlobal("XMLHttpRequest") is InteropDocSubject.JsGlobal) }
    @Test fun `js-WebSocket resolves to JsGlobal`() { assertTrue(jsGlobal("WebSocket") is InteropDocSubject.JsGlobal) }
    @Test fun `js-EventSource resolves to JsGlobal`() { assertTrue(jsGlobal("EventSource") is InteropDocSubject.JsGlobal) }
    @Test fun `js-MutationObserver resolves to JsGlobal`() { assertTrue(jsGlobal("MutationObserver") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IntersectionObserver resolves to JsGlobal`() { assertTrue(jsGlobal("IntersectionObserver") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ResizeObserver resolves to JsGlobal`() { assertTrue(jsGlobal("ResizeObserver") is InteropDocSubject.JsGlobal) }
    @Test fun `js-PerformanceObserver resolves to JsGlobal`() { assertTrue(jsGlobal("PerformanceObserver") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ReportingObserver resolves to JsGlobal`() { assertTrue(jsGlobal("ReportingObserver") is InteropDocSubject.JsGlobal) }
    @Test fun `js-TextEncoder resolves to JsGlobal`() { assertTrue(jsGlobal("TextEncoder") is InteropDocSubject.JsGlobal) }
    @Test fun `js-TextDecoder resolves to JsGlobal`() { assertTrue(jsGlobal("TextDecoder") is InteropDocSubject.JsGlobal) }
    @Test fun `js-DOMParser resolves to JsGlobal`() { assertTrue(jsGlobal("DOMParser") is InteropDocSubject.JsGlobal) }
    @Test fun `js-XMLSerializer resolves to JsGlobal`() { assertTrue(jsGlobal("XMLSerializer") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ImageData resolves to JsGlobal`() { assertTrue(jsGlobal("ImageData") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ImageBitmap resolves to JsGlobal`() { assertTrue(jsGlobal("ImageBitmap") is InteropDocSubject.JsGlobal) }
    @Test fun `js-OffscreenCanvas resolves to JsGlobal`() { assertTrue(jsGlobal("OffscreenCanvas") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Path2D resolves to JsGlobal`() { assertTrue(jsGlobal("Path2D") is InteropDocSubject.JsGlobal) }
    @Test fun `js-AudioContext resolves to JsGlobal`() { assertTrue(jsGlobal("AudioContext") is InteropDocSubject.JsGlobal) }
    @Test fun `js-OfflineAudioContext resolves to JsGlobal`() { assertTrue(jsGlobal("OfflineAudioContext") is InteropDocSubject.JsGlobal) }
    @Test fun `js-MediaStream resolves to JsGlobal`() { assertTrue(jsGlobal("MediaStream") is InteropDocSubject.JsGlobal) }
    @Test fun `js-MediaStreamTrack resolves to JsGlobal`() { assertTrue(jsGlobal("MediaStreamTrack") is InteropDocSubject.JsGlobal) }
    @Test fun `js-MediaRecorder resolves to JsGlobal`() { assertTrue(jsGlobal("MediaRecorder") is InteropDocSubject.JsGlobal) }
    @Test fun `js-RTCPeerConnection resolves to JsGlobal`() { assertTrue(jsGlobal("RTCPeerConnection") is InteropDocSubject.JsGlobal) }
    @Test fun `js-RTCSessionDescription resolves to JsGlobal`() { assertTrue(jsGlobal("RTCSessionDescription") is InteropDocSubject.JsGlobal) }
    @Test fun `js-RTCIceCandidate resolves to JsGlobal`() { assertTrue(jsGlobal("RTCIceCandidate") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Notification resolves to JsGlobal`() { assertTrue(jsGlobal("Notification") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Animation resolves to JsGlobal`() { assertTrue(jsGlobal("Animation") is InteropDocSubject.JsGlobal) }
    @Test fun `js-KeyframeEffect resolves to JsGlobal`() { assertTrue(jsGlobal("KeyframeEffect") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IDBFactory resolves to JsGlobal`() { assertTrue(jsGlobal("IDBFactory") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IDBDatabase resolves to JsGlobal`() { assertTrue(jsGlobal("IDBDatabase") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IDBObjectStore resolves to JsGlobal`() { assertTrue(jsGlobal("IDBObjectStore") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IDBIndex resolves to JsGlobal`() { assertTrue(jsGlobal("IDBIndex") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IDBCursor resolves to JsGlobal`() { assertTrue(jsGlobal("IDBCursor") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IDBTransaction resolves to JsGlobal`() { assertTrue(jsGlobal("IDBTransaction") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IDBKeyRange resolves to JsGlobal`() { assertTrue(jsGlobal("IDBKeyRange") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IDBRequest resolves to JsGlobal`() { assertTrue(jsGlobal("IDBRequest") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IDBOpenDBRequest resolves to JsGlobal`() { assertTrue(jsGlobal("IDBOpenDBRequest") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Cache resolves to JsGlobal`() { assertTrue(jsGlobal("Cache") is InteropDocSubject.JsGlobal) }
    @Test fun `js-CacheStorage resolves to JsGlobal`() { assertTrue(jsGlobal("CacheStorage") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ServiceWorker resolves to JsGlobal`() { assertTrue(jsGlobal("ServiceWorker") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ServiceWorkerRegistration resolves to JsGlobal`() { assertTrue(jsGlobal("ServiceWorkerRegistration") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ServiceWorkerContainer resolves to JsGlobal`() { assertTrue(jsGlobal("ServiceWorkerContainer") is InteropDocSubject.JsGlobal) }
    @Test fun `js-NodeFilter resolves to JsGlobal`() { assertTrue(jsGlobal("NodeFilter") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Intl resolves to JsGlobal`() { assertTrue(jsGlobal("Intl") is InteropDocSubject.JsGlobal) }

    // ─── Event type globals ───────────────────────────────────────────────────────

    @Test fun `js-UIEvent resolves to JsGlobal`() { assertTrue(jsGlobal("UIEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-MouseEvent resolves to JsGlobal`() { assertTrue(jsGlobal("MouseEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-PointerEvent resolves to JsGlobal`() { assertTrue(jsGlobal("PointerEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-KeyboardEvent resolves to JsGlobal`() { assertTrue(jsGlobal("KeyboardEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-WheelEvent resolves to JsGlobal`() { assertTrue(jsGlobal("WheelEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-TouchEvent resolves to JsGlobal`() { assertTrue(jsGlobal("TouchEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-FocusEvent resolves to JsGlobal`() { assertTrue(jsGlobal("FocusEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-InputEvent resolves to JsGlobal`() { assertTrue(jsGlobal("InputEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-DragEvent resolves to JsGlobal`() { assertTrue(jsGlobal("DragEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ClipboardEvent resolves to JsGlobal`() { assertTrue(jsGlobal("ClipboardEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-MessageEvent resolves to JsGlobal`() { assertTrue(jsGlobal("MessageEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ErrorEvent resolves to JsGlobal`() { assertTrue(jsGlobal("ErrorEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ProgressEvent resolves to JsGlobal`() { assertTrue(jsGlobal("ProgressEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-StorageEvent resolves to JsGlobal`() { assertTrue(jsGlobal("StorageEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-PopStateEvent resolves to JsGlobal`() { assertTrue(jsGlobal("PopStateEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-HashChangeEvent resolves to JsGlobal`() { assertTrue(jsGlobal("HashChangeEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-BeforeUnloadEvent resolves to JsGlobal`() { assertTrue(jsGlobal("BeforeUnloadEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-PageTransitionEvent resolves to JsGlobal`() { assertTrue(jsGlobal("PageTransitionEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-AnimationEvent resolves to JsGlobal`() { assertTrue(jsGlobal("AnimationEvent") is InteropDocSubject.JsGlobal) }
    @Test fun `js-TransitionEvent resolves to JsGlobal`() { assertTrue(jsGlobal("TransitionEvent") is InteropDocSubject.JsGlobal) }

    // ─── DOM element type globals ─────────────────────────────────────────────────

    @Test fun `js-Node resolves to JsGlobal`() { assertTrue(jsGlobal("Node") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Element resolves to JsGlobal`() { assertTrue(jsGlobal("Element") is InteropDocSubject.JsGlobal) }
    @Test fun `js-HTMLElement resolves to JsGlobal`() { assertTrue(jsGlobal("HTMLElement") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Document resolves to JsGlobal`() { assertTrue(jsGlobal("Document") is InteropDocSubject.JsGlobal) }
    @Test fun `js-DocumentFragment resolves to JsGlobal`() { assertTrue(jsGlobal("DocumentFragment") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ShadowRoot resolves to JsGlobal`() { assertTrue(jsGlobal("ShadowRoot") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Text resolves to JsGlobal`() { assertTrue(jsGlobal("Text") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Comment resolves to JsGlobal`() { assertTrue(jsGlobal("Comment") is InteropDocSubject.JsGlobal) }
    @Test fun `js-NodeList resolves to JsGlobal`() { assertTrue(jsGlobal("NodeList") is InteropDocSubject.JsGlobal) }
    @Test fun `js-HTMLCollection resolves to JsGlobal`() { assertTrue(jsGlobal("HTMLCollection") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Range resolves to JsGlobal`() { assertTrue(jsGlobal("Range") is InteropDocSubject.JsGlobal) }
    @Test fun `js-Selection resolves to JsGlobal`() { assertTrue(jsGlobal("Selection") is InteropDocSubject.JsGlobal) }
    @Test fun `js-TreeWalker resolves to JsGlobal`() { assertTrue(jsGlobal("TreeWalker") is InteropDocSubject.JsGlobal) }
    @Test fun `js-MutationRecord resolves to JsGlobal`() { assertTrue(jsGlobal("MutationRecord") is InteropDocSubject.JsGlobal) }
    @Test fun `js-IntersectionObserverEntry resolves to JsGlobal`() { assertTrue(jsGlobal("IntersectionObserverEntry") is InteropDocSubject.JsGlobal) }
    @Test fun `js-ResizeObserverEntry resolves to JsGlobal`() { assertTrue(jsGlobal("ResizeObserverEntry") is InteropDocSubject.JsGlobal) }
    @Test fun `js-DOMRect resolves to JsGlobal`() { assertTrue(jsGlobal("DOMRect") is InteropDocSubject.JsGlobal) }
    @Test fun `js-DOMMatrix resolves to JsGlobal`() { assertTrue(jsGlobal("DOMMatrix") is InteropDocSubject.JsGlobal) }
    @Test fun `js-DOMPoint resolves to JsGlobal`() { assertTrue(jsGlobal("DOMPoint") is InteropDocSubject.JsGlobal) }

    // ─── CSS globals ──────────────────────────────────────────────────────────────

    @Test fun `js-CSS resolves to JsGlobal`() { assertTrue(jsGlobal("CSS") is InteropDocSubject.JsGlobal) }
    @Test fun `js-CSSStyleDeclaration resolves to JsGlobal`() { assertTrue(jsGlobal("CSSStyleDeclaration") is InteropDocSubject.JsGlobal) }
    @Test fun `js-CSSStyleSheet resolves to JsGlobal`() { assertTrue(jsGlobal("CSSStyleSheet") is InteropDocSubject.JsGlobal) }
    @Test fun `js-StyleSheet resolves to JsGlobal`() { assertTrue(jsGlobal("StyleSheet") is InteropDocSubject.JsGlobal) }
    @Test fun `js-MediaQueryList resolves to JsGlobal`() { assertTrue(jsGlobal("MediaQueryList") is InteropDocSubject.JsGlobal) }

    // ─── Canvas/WebGL globals ─────────────────────────────────────────────────────

    @Test fun `js-CanvasRenderingContext2D resolves to JsGlobal`() { assertTrue(jsGlobal("CanvasRenderingContext2D") is InteropDocSubject.JsGlobal) }
    @Test fun `js-WebGLRenderingContext resolves to JsGlobal`() { assertTrue(jsGlobal("WebGLRenderingContext") is InteropDocSubject.JsGlobal) }
    @Test fun `js-WebGL2RenderingContext resolves to JsGlobal`() { assertTrue(jsGlobal("WebGL2RenderingContext") is InteropDocSubject.JsGlobal) }

    // ─── Chain resolution ─────────────────────────────────────────────────────────

    @Test fun `js-document-createElement resolves to JsChain`() {
        val s = jsDot("document.createElement") as InteropDocSubject.JsChain
        assertEquals(listOf("document", "createElement"), s.segments)
        assertEquals("Document", s.declaringType)
        assertEquals("Element", s.member.returns)
    }

    @Test fun `js-document-getElementById resolves to JsChain`() {
        val s = jsDot("document.getElementById") as InteropDocSubject.JsChain
        assertEquals("Document", s.declaringType)
    }

    @Test fun `js-document-querySelector resolves to JsChain`() {
        val s = jsDot("document.querySelector") as InteropDocSubject.JsChain
        assertEquals("Document", s.declaringType)
        assertEquals("Element", s.member.returns)
    }

    @Test fun `js-document-createRange resolves to JsChain`() {
        val s = jsDot("document.createRange") as InteropDocSubject.JsChain
        assertEquals("Document", s.declaringType)
        assertEquals("Range", s.member.returns)
    }

    @Test fun `js-navigator-userAgent resolves to JsChain`() {
        val s = jsDot("navigator.userAgent") as InteropDocSubject.JsChain
        assertEquals("Navigator", s.declaringType)
        assertEquals("string", s.member.type)
    }

    @Test fun `js-navigator-language resolves to JsChain`() {
        val s = jsDot("navigator.language") as InteropDocSubject.JsChain
        assertEquals("Navigator", s.declaringType)
    }

    @Test fun `js-location-href resolves to JsChain`() {
        val s = jsDot("location.href") as InteropDocSubject.JsChain
        assertEquals("Location", s.declaringType)
        assertEquals("string", s.member.type)
    }

    @Test fun `js-location-pathname resolves to JsChain`() {
        val s = jsDot("location.pathname") as InteropDocSubject.JsChain
        assertEquals("Location", s.declaringType)
    }

    @Test fun `js-history-pushState resolves to JsChain`() {
        val s = jsDot("history.pushState") as InteropDocSubject.JsChain
        assertEquals("History", s.declaringType)
    }

    @Test fun `js-history-back resolves to JsChain`() {
        val s = jsDot("history.back") as InteropDocSubject.JsChain
        assertEquals("History", s.declaringType)
    }

    @Test fun `js-console-log resolves to JsChain`() {
        val s = jsDot("console.log") as InteropDocSubject.JsChain
        assertEquals("Console", s.declaringType)
    }

    @Test fun `js-console-error resolves to JsChain`() {
        val s = jsDot("console.error") as InteropDocSubject.JsChain
        assertEquals("Console", s.declaringType)
    }

    @Test fun `js-console-warn resolves to JsChain`() {
        val s = jsDot("console.warn") as InteropDocSubject.JsChain
        assertEquals("Console", s.declaringType)
    }

    @Test fun `js-performance-now resolves to JsChain`() {
        val s = jsDot("performance.now") as InteropDocSubject.JsChain
        assertEquals("Performance", s.declaringType)
        assertEquals("number", s.member.returns)
    }

    @Test fun `js-performance-mark resolves to JsChain`() {
        val s = jsDot("performance.mark") as InteropDocSubject.JsChain
        assertEquals("Performance", s.declaringType)
    }

    @Test fun `js-crypto-subtle resolves to JsChain`() {
        val s = jsDot("crypto.subtle") as InteropDocSubject.JsChain
        assertEquals("Crypto", s.declaringType)
        assertEquals("SubtleCrypto", s.member.type)
    }

    @Test fun `js-crypto-randomUUID resolves to JsChain`() {
        val s = jsDot("crypto.randomUUID") as InteropDocSubject.JsChain
        assertEquals("Crypto", s.declaringType)
    }

    @Test fun `js-localStorage-getItem resolves to JsChain`() {
        val s = jsDot("localStorage.getItem") as InteropDocSubject.JsChain
        assertEquals("Storage", s.declaringType)
    }

    @Test fun `js-localStorage-setItem resolves to JsChain`() {
        val s = jsDot("localStorage.setItem") as InteropDocSubject.JsChain
        assertEquals("Storage", s.declaringType)
    }

    @Test fun `js-window-innerWidth resolves to JsChain`() {
        val s = jsDot("window.innerWidth") as InteropDocSubject.JsChain
        assertEquals("Window", s.declaringType)
    }

    @Test fun `js-screen-width resolves to JsChain`() {
        val s = jsDot("screen.width") as InteropDocSubject.JsChain
        assertEquals("Screen", s.declaringType)
    }

    // ─── Member access with known receiver type ───────────────────────────────────

    @Test fun `dot-createElement on Document resolves to Member`() {
        val s = resolve(null, ".createElement", receiverType = "Document") as InteropDocSubject.Member
        assertEquals("createElement", s.name)
        assertEquals(false, s.asProperty)
        assertEquals("Document", s.declaringType)
    }

    @Test fun `dot-querySelector on Document resolves to Member`() {
        val s = resolve(null, ".querySelector", receiverType = "Document") as InteropDocSubject.Member
        assertEquals("Document", s.declaringType)
    }

    @Test fun `dot-property-getElementById on Document resolves as method`() {
        val s = resolve(null, ".getElementById", receiverType = "Document") as InteropDocSubject.Member
        assertEquals(false, s.asProperty)
    }

    @Test fun `dot-property-title on Document resolves to Member`() {
        val s = resolve(null, ".-title", receiverType = "Document") as InteropDocSubject.Member
        assertEquals("title", s.name)
        assertEquals(true, s.asProperty)
    }

    @Test fun `dot-addEventListener on Element resolves to Member`() {
        val s = resolve(null, ".addEventListener", receiverType = "Element") as InteropDocSubject.Member
        assertEquals("Element", s.declaringType)
    }

    @Test fun `dot-getAttribute on Element resolves to Member`() {
        val s = resolve(null, ".getAttribute", receiverType = "Element") as InteropDocSubject.Member
        assertEquals("Element", s.declaringType)
    }

    @Test fun `dot-property-innerHTML on Element resolves to Member`() {
        val s = resolve(null, ".-innerHTML", receiverType = "Element") as InteropDocSubject.Member
        assertEquals(true, s.asProperty)
        assertEquals("Element", s.declaringType)
    }

    @Test fun `dot-property-className on Element resolves to Member`() {
        val s = resolve(null, ".-className", receiverType = "Element") as InteropDocSubject.Member
        assertEquals("string", s.member.type)
    }

    @Test fun `dot-getItem on Storage resolves to Member`() {
        val s = resolve(null, ".getItem", receiverType = "Storage") as InteropDocSubject.Member
        assertEquals("Storage", s.declaringType)
        assertEquals("string", s.member.returns)
    }

    @Test fun `dot-setItem on Storage resolves to Member`() {
        val s = resolve(null, ".setItem", receiverType = "Storage") as InteropDocSubject.Member
        assertEquals("Storage", s.declaringType)
    }

    @Test fun `dot-property-length on Storage resolves to Member`() {
        val s = resolve(null, ".-length", receiverType = "Storage") as InteropDocSubject.Member
        assertEquals("number", s.member.type)
    }

    @Test fun `dot-log on Console resolves to Member`() {
        val s = resolve(null, ".log", receiverType = "Console") as InteropDocSubject.Member
        assertEquals("Console", s.declaringType)
    }

    @Test fun `dot-now on Performance resolves to Member`() {
        val s = resolve(null, ".now", receiverType = "Performance") as InteropDocSubject.Member
        assertEquals("Performance", s.declaringType)
    }

    @Test fun `dot-property-subtle on Crypto resolves to Member`() {
        val s = resolve(null, ".-subtle", receiverType = "Crypto") as InteropDocSubject.Member
        assertEquals("SubtleCrypto", s.member.type)
    }

    @Test fun `dot-pushState on History resolves to Member`() {
        val s = resolve(null, ".pushState", receiverType = "History") as InteropDocSubject.Member
        assertEquals("History", s.declaringType)
    }

    @Test fun `dot-property-href on Location resolves to Member`() {
        val s = resolve(null, ".-href", receiverType = "Location") as InteropDocSubject.Member
        assertEquals("Location", s.declaringType)
        assertEquals("string", s.member.type)
    }

    @Test fun `dot-assign on Location resolves to Member`() {
        val s = resolve(null, ".assign", receiverType = "Location") as InteropDocSubject.Member
        assertEquals("Location", s.declaringType)
    }

    @Test fun `dot-observe on MutationObserver resolves to Member`() {
        val s = resolve(null, ".observe", receiverType = "MutationObserver") as InteropDocSubject.Member
        assertEquals("MutationObserver", s.declaringType)
    }

    @Test fun `dot-disconnect on MutationObserver resolves to Member`() {
        val s = resolve(null, ".disconnect", receiverType = "MutationObserver") as InteropDocSubject.Member
        assertEquals("MutationObserver", s.declaringType)
    }

    @Test fun `dot-property-matches on IntersectionObserver resolves to Member`() {
        val s = resolve(null, ".-matches", receiverType = "MediaQueryList") as InteropDocSubject.Member
        assertEquals("boolean", s.member.type)
    }

    @Test fun `dot-getReader on ReadableStream resolves to Member`() {
        val s = resolve(null, ".getReader", receiverType = "ReadableStream") as InteropDocSubject.Member
        assertEquals("ReadableStream", s.declaringType)
    }

    @Test fun `dot-property-locked on WritableStream resolves to Member`() {
        val s = resolve(null, ".-locked", receiverType = "WritableStream") as InteropDocSubject.Member
        assertEquals("boolean", s.member.type)
    }

    @Test fun `dot-encode on TextEncoder resolves to Member`() {
        val s = resolve(null, ".encode", receiverType = "TextEncoder") as InteropDocSubject.Member
        assertEquals("TextEncoder", s.declaringType)
    }

    @Test fun `dot-decode on TextDecoder resolves to Member`() {
        val s = resolve(null, ".decode", receiverType = "TextDecoder") as InteropDocSubject.Member
        assertEquals("TextDecoder", s.declaringType)
    }

    @Test fun `dot-getRandomValues on Crypto resolves to Member`() {
        val s = resolve(null, ".getRandomValues", receiverType = "Crypto") as InteropDocSubject.Member
        assertEquals("Crypto", s.declaringType)
    }

    @Test fun `dot-send on XMLHttpRequest resolves to Member`() {
        val s = resolve(null, ".send", receiverType = "XMLHttpRequest") as InteropDocSubject.Member
        assertEquals("XMLHttpRequest", s.declaringType)
    }

    @Test fun `dot-send on WebSocket resolves to Member`() {
        val s = resolve(null, ".send", receiverType = "WebSocket") as InteropDocSubject.Member
        assertEquals("WebSocket", s.declaringType)
    }

    // ─── Inheritance: HTMLElement extends Element ─────────────────────────────────

    @Test fun `inherited addEventListener accessible on HTMLElement`() {
        // pickMember shows the receiver type, not the declaring interface — inheritance is walked
        // internally but the hover shows "HTMLElement" since that's what the user wrote.
        val s = resolve(null, ".addEventListener", receiverType = "HTMLElement") as InteropDocSubject.Member
        assertEquals("HTMLElement", s.declaringType)
        assertEquals(false, s.asProperty)
    }

    @Test fun `inherited getAttribute accessible on HTMLElement`() {
        val s = resolve(null, ".getAttribute", receiverType = "HTMLElement") as InteropDocSubject.Member
        assertEquals("HTMLElement", s.declaringType)
    }

    @Test fun `own style property accessible on HTMLElement`() {
        val s = resolve(null, ".-style", receiverType = "HTMLElement") as InteropDocSubject.Member
        assertEquals("HTMLElement", s.declaringType)
    }

    @Test fun `own tabIndex property accessible on HTMLElement`() {
        val s = resolve(null, ".-tabIndex", receiverType = "HTMLElement") as InteropDocSubject.Member
        assertEquals("HTMLElement", s.declaringType)
    }

    // ─── Member fallthrough sampler (unknown receiver) ────────────────────────────

    @Test fun `dot-innerHTML with no receiver falls through to sampler`() {
        val s = resolve(null, ".-innerHTML", receiverType = null) as InteropDocSubject.Member
        assertEquals("innerHTML", s.name)
        assertEquals(true, s.asProperty)
    }

    @Test fun `dot-addEventListener with no receiver falls through to sampler`() {
        val s = resolve(null, ".addEventListener", receiverType = null) as InteropDocSubject.Member
        assertEquals("addEventListener", s.name)
        assertEquals(false, s.asProperty)
    }

    @Test fun `dot-property-userAgent with no receiver falls through to sampler`() {
        val s = resolve(null, ".-userAgent", receiverType = null) as InteropDocSubject.Member
        assertEquals("userAgent", s.name)
        assertTrue(s.asProperty)
    }

    // ─── typeof companion sanitization ────────────────────────────────────────────

    @Test fun `TYPE-dollar-NodeFilter member SHOW_ELEMENT shows as NodeFilter`() {
        val s = resolve(null, ".-SHOW_ELEMENT", receiverType = "TYPE\$NodeFilter") as InteropDocSubject.Member
        assertEquals("NodeFilter", s.declaringType)
        assertEquals("SHOW_ELEMENT", s.name)
    }

    @Test fun `TYPE-dollar-NodeFilter member acceptNode prefers interface over companion`() {
        val s = resolve(null, ".acceptNode", receiverType = "TYPE\$NodeFilter") as InteropDocSubject.Member
        assertEquals("NodeFilter", s.declaringType)
        assertEquals(false, s.asProperty)
    }

    @Test fun `TYPE-dollar-Math member random shows as Math`() {
        val s = resolve(null, ".random", receiverType = "TYPE\$Math") as InteropDocSubject.Member
        assertEquals("Math", s.declaringType)
    }

    @Test fun `sanitizeTypeName strips TYPE-dollar prefix`() {
        assertEquals("NodeFilter", InteropDocResolver.sanitizeTypeName("TYPE\$NodeFilter"))
        assertEquals("Math", InteropDocResolver.sanitizeTypeName("TYPE\$Math"))
        assertEquals("JSON", InteropDocResolver.sanitizeTypeName("TYPE\$JSON"))
    }

    @Test fun `sanitizeTypeName leaves plain names unchanged`() {
        assertEquals("Document", InteropDocResolver.sanitizeTypeName("Document"))
        assertEquals("Element", InteropDocResolver.sanitizeTypeName("Element"))
    }

    // ─── Constructor distinction ──────────────────────────────────────────────────

    @Test fun `js-URL isConstructor true`() {
        val s = jsGlobal("URL") as InteropDocSubject.JsGlobal
        assertTrue(s.isConstructor)
    }

    @Test fun `js-Blob isConstructor true`() {
        assertTrue((jsGlobal("Blob") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-Worker isConstructor true`() {
        assertTrue((jsGlobal("Worker") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-XMLHttpRequest isConstructor true`() {
        assertTrue((jsGlobal("XMLHttpRequest") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-WebSocket isConstructor true`() {
        assertTrue((jsGlobal("WebSocket") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-Error isConstructor true`() {
        assertTrue((jsGlobal("Error") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-Array isConstructor true`() {
        assertTrue((jsGlobal("Array") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-Promise isConstructor true`() {
        assertTrue((jsGlobal("Promise") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-MutationObserver isConstructor true`() {
        assertTrue((jsGlobal("MutationObserver") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-TextEncoder isConstructor true`() {
        assertTrue((jsGlobal("TextEncoder") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-NodeFilter isConstructor true`() {
        assertTrue((jsGlobal("NodeFilter") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-document isConstructor false`() {
        assertFalse((jsGlobal("document") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-window isConstructor false`() {
        assertFalse((jsGlobal("window") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-performance isConstructor false`() {
        assertFalse((jsGlobal("performance") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-localStorage isConstructor false`() {
        assertFalse((jsGlobal("localStorage") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-navigator isConstructor false`() {
        assertFalse((jsGlobal("navigator") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-console isConstructor false`() {
        assertFalse((jsGlobal("console") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-Math isConstructor false - singleton object`() {
        assertFalse((jsGlobal("Math") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-JSON isConstructor false - singleton object`() {
        assertFalse((jsGlobal("JSON") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-Reflect isConstructor false - singleton object`() {
        assertFalse((jsGlobal("Reflect") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `js-Atomics isConstructor false - singleton object`() {
        assertFalse((jsGlobal("Atomics") as InteropDocSubject.JsGlobal).isConstructor)
    }

    @Test fun `formatter shows class and constructor label for constructor globals`() {
        val html = InteropDocFormatter.format(jsGlobal("URL"))
        assertNotNull(html)
        assertTrue("expected 'class URL' in definition", html!!.contains("class URL"))
        assertTrue("expected 'constructor' label", html.contains("constructor"))
    }

    @Test fun `formatter shows global value label for instance globals`() {
        val html = InteropDocFormatter.format(jsGlobal("document"))
        assertNotNull(html)
        assertTrue("expected 'global value' label", html!!.contains("global value"))
        assertTrue("expected type name Document", html.contains("Document"))
    }

    @Test fun `formatter constructor does not show TYPE-dollar prefix`() {
        val html = InteropDocFormatter.format(jsGlobal("URL"))
        assertNotNull(html)
        assertTrue("TYPE\$ prefix must not appear in output", !html!!.contains("TYPE\$"))
    }

    // ─── Unknown resolves to Unknown ──────────────────────────────────────────────

    @Test fun `unknown js global resolves to Unknown`() {
        assertEquals(InteropDocSubject.Unknown, jsGlobal("__nonexistent__"))
    }

    @Test fun `unknown member on Document resolves to Unknown`() {
        val s = resolve(null, ".-__nonexistent__", receiverType = "Document")
        assertEquals(InteropDocSubject.Unknown, s)
    }

    // ─── Index builder ────────────────────────────────────────────────────────────

    companion object {

        private fun fn(returns: String = "void", vararg params: JsParam) =
            JsMember(kind = "method", returns = returns, params = params.toList())

        private fun prop(type: String) = JsMember(kind = "property", type = type)

        private fun iface(vararg members: Pair<String, JsMember>, extends: List<String> = emptyList()) =
            JsInterface(extends = extends, members = members.associate { (k, v) -> k to listOf(v) })

        fun buildBrowserIndex(): JsSymbolIndex {
            val index = JsSymbolIndex()

            // ── Interfaces ─────────────────────────────────────────────────────

            val ifaceMap = mapOf(
                "Window" to iface(
                    "innerWidth" to prop("number"), "innerHeight" to prop("number"),
                    "outerWidth" to prop("number"), "outerHeight" to prop("number"),
                    "scrollX" to prop("number"), "scrollY" to prop("number"),
                    "devicePixelRatio" to prop("number"), "name" to prop("string"),
                    "pageXOffset" to prop("number"), "pageYOffset" to prop("number"),
                ),
                "Document" to iface(
                    "createElement" to fn("Element", JsParam("tagName", "string")),
                    "createRange" to fn("Range"),
                    "getElementById" to fn("Element", JsParam("id", "string")),
                    "querySelector" to fn("Element", JsParam("selectors", "string")),
                    "querySelectorAll" to fn("NodeList", JsParam("selectors", "string")),
                    "createDocumentFragment" to fn("DocumentFragment"),
                    "createTextNode" to fn("Text", JsParam("data", "string")),
                    "createComment" to fn("Comment", JsParam("data", "string")),
                    "createTreeWalker" to fn("TreeWalker", JsParam("root", "Node")),
                    "adoptNode" to fn("Node", JsParam("node", "Node")),
                    "importNode" to fn("Node", JsParam("node", "Node")),
                    "hasFocus" to fn("boolean"),
                    "elementFromPoint" to fn("Element", JsParam("x", "number"), JsParam("y", "number")),
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
                    "compatMode" to prop("string"),
                    "contentType" to prop("string"),
                ),
                "Element" to iface(
                    "tagName" to prop("string"), "id" to prop("string"),
                    "className" to prop("string"), "innerHTML" to prop("string"),
                    "outerHTML" to prop("string"), "textContent" to prop("string"),
                    "scrollTop" to prop("number"), "scrollLeft" to prop("number"),
                    "scrollWidth" to prop("number"), "scrollHeight" to prop("number"),
                    "clientTop" to prop("number"), "clientLeft" to prop("number"),
                    "clientWidth" to prop("number"), "clientHeight" to prop("number"),
                    "namespaceURI" to prop("string"), "localName" to prop("string"),
                    "getAttribute" to fn("string", JsParam("qualifiedName", "string")),
                    "setAttribute" to fn("void", JsParam("qualifiedName", "string"), JsParam("value", "string")),
                    "hasAttribute" to fn("boolean", JsParam("qualifiedName", "string")),
                    "removeAttribute" to fn("void", JsParam("qualifiedName", "string")),
                    "addEventListener" to fn("void", JsParam("type", "string"), JsParam("listener", "Function")),
                    "removeEventListener" to fn("void", JsParam("type", "string"), JsParam("listener", "Function")),
                    "dispatchEvent" to fn("boolean", JsParam("event", "Event")),
                    "getBoundingClientRect" to fn("DOMRect"),
                    "matches" to fn("boolean", JsParam("selectors", "string")),
                    "closest" to fn("Element", JsParam("selectors", "string")),
                    "querySelector" to fn("Element", JsParam("selectors", "string")),
                    "querySelectorAll" to fn("NodeList", JsParam("selectors", "string")),
                    "append" to fn("void"), "prepend" to fn("void"),
                    "remove" to fn("void"), "replaceWith" to fn("void"),
                    "insertAdjacentElement" to fn("Element"),
                    "insertAdjacentHTML" to fn("void"),
                    "insertAdjacentText" to fn("void"),
                    "after" to fn("void"), "before" to fn("void"),
                ),
                "HTMLElement" to iface(
                    "style" to prop("CSSStyleDeclaration"),
                    "dataset" to prop("DOMStringMap"),
                    "tabIndex" to prop("number"),
                    "hidden" to prop("boolean"),
                    "offsetWidth" to prop("number"), "offsetHeight" to prop("number"),
                    "offsetTop" to prop("number"), "offsetLeft" to prop("number"),
                    "offsetParent" to prop("Element"),
                    "draggable" to prop("boolean"),
                    "contentEditable" to prop("string"),
                    "isContentEditable" to prop("boolean"),
                    "lang" to prop("string"), "dir" to prop("string"),
                    "title" to prop("string"), "accessKey" to prop("string"),
                    "spellcheck" to prop("boolean"), "translate" to prop("boolean"),
                    "click" to fn("void"), "focus" to fn("void"), "blur" to fn("void"),
                    extends = listOf("Element"),
                ),
                "Node" to iface(
                    "nodeType" to prop("number"), "nodeName" to prop("string"),
                    "nodeValue" to prop("string"), "textContent" to prop("string"),
                    "parentNode" to prop("Node"), "parentElement" to prop("Element"),
                    "childNodes" to prop("NodeList"), "firstChild" to prop("Node"),
                    "lastChild" to prop("Node"), "nextSibling" to prop("Node"),
                    "previousSibling" to prop("Node"), "ownerDocument" to prop("Document"),
                    "isConnected" to prop("boolean"),
                    "cloneNode" to fn("Node", JsParam("deep", "boolean", optional = true)),
                    "appendChild" to fn("Node", JsParam("node", "Node")),
                    "insertBefore" to fn("Node", JsParam("node", "Node"), JsParam("child", "Node")),
                    "removeChild" to fn("Node", JsParam("child", "Node")),
                    "replaceChild" to fn("Node", JsParam("node", "Node"), JsParam("child", "Node")),
                    "contains" to fn("boolean", JsParam("other", "Node")),
                    "hasChildNodes" to fn("boolean"), "normalize" to fn("void"),
                    "getRootNode" to fn("Node"),
                ),
                "Navigator" to iface(
                    "userAgent" to prop("string"), "language" to prop("string"),
                    "languages" to prop("ReadonlyArray"), "platform" to prop("string"),
                    "cookieEnabled" to prop("boolean"), "onLine" to prop("boolean"),
                    "hardwareConcurrency" to prop("number"), "maxTouchPoints" to prop("number"),
                    "vendor" to prop("string"), "appName" to prop("string"),
                    "appVersion" to prop("string"), "appCodeName" to prop("string"),
                    "doNotTrack" to prop("string"), "deviceMemory" to prop("number"),
                    "geolocation" to prop("Geolocation"),
                    "sendBeacon" to fn("boolean", JsParam("url", "string")),
                    "vibrate" to fn("boolean", JsParam("pattern", "number")),
                    "share" to fn("Promise"),
                    "registerProtocolHandler" to fn("void"),
                ),
                "Location" to iface(
                    "href" to prop("string"), "protocol" to prop("string"),
                    "host" to prop("string"), "hostname" to prop("string"),
                    "port" to prop("string"), "pathname" to prop("string"),
                    "search" to prop("string"), "hash" to prop("string"),
                    "origin" to prop("string"),
                    "assign" to fn("void", JsParam("url", "string")),
                    "replace" to fn("void", JsParam("url", "string")),
                    "reload" to fn("void"),
                ),
                "History" to iface(
                    "length" to prop("number"), "state" to prop("any"),
                    "scrollRestoration" to prop("string"),
                    "pushState" to fn("void", JsParam("data", "any"), JsParam("unused", "string")),
                    "replaceState" to fn("void", JsParam("data", "any"), JsParam("unused", "string")),
                    "go" to fn("void", JsParam("delta", "number", optional = true)),
                    "back" to fn("void"), "forward" to fn("void"),
                ),
                "Screen" to iface(
                    "width" to prop("number"), "height" to prop("number"),
                    "availWidth" to prop("number"), "availHeight" to prop("number"),
                    "colorDepth" to prop("number"), "pixelDepth" to prop("number"),
                ),
                "Console" to iface(
                    "log" to fn("void"), "warn" to fn("void"), "error" to fn("void"),
                    "info" to fn("void"), "debug" to fn("void"), "trace" to fn("void"),
                    "dir" to fn("void"), "table" to fn("void"),
                    "group" to fn("void"), "groupEnd" to fn("void"),
                    "groupCollapsed" to fn("void"),
                    "time" to fn("void", JsParam("label", "string", optional = true)),
                    "timeEnd" to fn("void", JsParam("label", "string", optional = true)),
                    "timeLog" to fn("void"), "count" to fn("void"),
                    "countReset" to fn("void"), "assert" to fn("void"),
                    "clear" to fn("void"),
                ),
                "Performance" to iface(
                    "now" to fn("number"),
                    "mark" to fn("PerformanceMark", JsParam("markName", "string")),
                    "measure" to fn("PerformanceMeasure", JsParam("measureName", "string")),
                    "getEntries" to fn("PerformanceEntryList"),
                    "getEntriesByName" to fn("PerformanceEntryList", JsParam("name", "string")),
                    "getEntriesByType" to fn("PerformanceEntryList", JsParam("type", "string")),
                    "clearMarks" to fn("void"), "clearMeasures" to fn("void"),
                    "timeOrigin" to prop("number"),
                ),
                "Crypto" to iface(
                    "subtle" to prop("SubtleCrypto"),
                    "getRandomValues" to fn("ArrayBufferView", JsParam("array", "ArrayBufferView")),
                    "randomUUID" to fn("string"),
                ),
                "Storage" to iface(
                    "length" to prop("number"),
                    "key" to fn("string", JsParam("index", "number")),
                    "getItem" to fn("string", JsParam("key", "string")),
                    "setItem" to fn("void", JsParam("key", "string"), JsParam("value", "string")),
                    "removeItem" to fn("void", JsParam("key", "string")),
                    "clear" to fn("void"),
                ),
                "IDBFactory" to iface(
                    "open" to fn("IDBOpenDBRequest", JsParam("name", "string")),
                    "deleteDatabase" to fn("IDBOpenDBRequest", JsParam("name", "string")),
                    "cmp" to fn("number"), "databases" to fn("Promise"),
                ),
                "MutationObserver" to iface(
                    "observe" to fn("void", JsParam("target", "Node"), JsParam("options", "MutationObserverInit", optional = true)),
                    "disconnect" to fn("void"), "takeRecords" to fn("MutationRecord"),
                ),
                "IntersectionObserver" to iface(
                    "root" to prop("Element"), "rootMargin" to prop("string"),
                    "thresholds" to prop("ReadonlyArray"),
                    "observe" to fn("void", JsParam("target", "Element")),
                    "unobserve" to fn("void", JsParam("target", "Element")),
                    "disconnect" to fn("void"), "takeRecords" to fn("IntersectionObserverEntry"),
                ),
                "ResizeObserver" to iface(
                    "observe" to fn("void", JsParam("target", "Element")),
                    "unobserve" to fn("void", JsParam("target", "Element")),
                    "disconnect" to fn("void"),
                ),
                "MediaQueryList" to iface(
                    "matches" to prop("boolean"), "media" to prop("string"),
                    "addListener" to fn("void"), "removeListener" to fn("void"),
                    "addEventListener" to fn("void"), "removeEventListener" to fn("void"),
                ),
                "ReadableStream" to iface(
                    "locked" to prop("boolean"),
                    "getReader" to fn("ReadableStreamDefaultReader"),
                    "cancel" to fn("Promise"), "tee" to fn("Array"),
                    "pipeTo" to fn("Promise", JsParam("destination", "WritableStream")),
                    "pipeThrough" to fn("ReadableStream", JsParam("transform", "TransformStream")),
                ),
                "WritableStream" to iface(
                    "locked" to prop("boolean"),
                    "getWriter" to fn("WritableStreamDefaultWriter"),
                    "close" to fn("Promise"), "abort" to fn("Promise"),
                ),
                "TransformStream" to iface(
                    "readable" to prop("ReadableStream"), "writable" to prop("WritableStream"),
                ),
                "TextEncoder" to iface(
                    "encoding" to prop("string"),
                    "encode" to fn("Uint8Array", JsParam("input", "string", optional = true)),
                    "encodeInto" to fn("TextEncoderEncodeIntoResult", JsParam("source", "string"), JsParam("destination", "Uint8Array")),
                ),
                "TextDecoder" to iface(
                    "encoding" to prop("string"), "fatal" to prop("boolean"),
                    "ignoreBOM" to prop("boolean"),
                    "decode" to fn("string", JsParam("input", "AllowSharedBufferSource", optional = true)),
                ),
                "XMLHttpRequest" to iface(
                    "readyState" to prop("number"), "status" to prop("number"),
                    "statusText" to prop("string"), "response" to prop("any"),
                    "responseText" to prop("string"), "responseType" to prop("string"),
                    "responseURL" to prop("string"), "timeout" to prop("number"),
                    "withCredentials" to prop("boolean"),
                    "open" to fn("void", JsParam("method", "string"), JsParam("url", "string")),
                    "send" to fn("void", JsParam("body", "Document", optional = true)),
                    "abort" to fn("void"),
                    "getResponseHeader" to fn("string", JsParam("name", "string")),
                    "getAllResponseHeaders" to fn("string"),
                    "setRequestHeader" to fn("void", JsParam("name", "string"), JsParam("value", "string")),
                    "addEventListener" to fn("void"), "removeEventListener" to fn("void"),
                ),
                "WebSocket" to iface(
                    "readyState" to prop("number"), "url" to prop("string"),
                    "protocol" to prop("string"), "extensions" to prop("string"),
                    "bufferedAmount" to prop("number"), "binaryType" to prop("string"),
                    "send" to fn("void", JsParam("data", "string")),
                    "close" to fn("void"),
                    "CONNECTING" to prop("number"), "OPEN" to prop("number"),
                    "CLOSING" to prop("number"), "CLOSED" to prop("number"),
                    "addEventListener" to fn("void"), "removeEventListener" to fn("void"),
                ),
                "NodeFilter" to iface(
                    "acceptNode" to fn("number", JsParam("node", "Node")),
                ),
                "TYPE\$NodeFilter" to iface(
                    "SHOW_ELEMENT" to prop("number"), "SHOW_TEXT" to prop("number"),
                    "SHOW_COMMENT" to prop("number"), "FILTER_ACCEPT" to prop("number"),
                    "FILTER_REJECT" to prop("number"), "FILTER_SKIP" to prop("number"),
                    "acceptNode" to fn("number", JsParam("node", "Node")),
                ),
                "Math" to iface(
                    "PI" to prop("number"), "E" to prop("number"),
                    "abs" to fn("number", JsParam("x", "number")),
                    "ceil" to fn("number", JsParam("x", "number")),
                    "floor" to fn("number", JsParam("x", "number")),
                    "round" to fn("number", JsParam("x", "number")),
                    "sqrt" to fn("number", JsParam("x", "number")),
                    "pow" to fn("number", JsParam("x", "number"), JsParam("y", "number")),
                    "max" to fn("number"), "min" to fn("number"),
                    "random" to fn("number"), "log" to fn("number", JsParam("x", "number")),
                    "sin" to fn("number"), "cos" to fn("number"), "tan" to fn("number"),
                ),
                "TYPE\$Math" to iface(
                    "random" to fn("number"),
                    "PI" to prop("number"),
                ),
                "JSON" to iface(
                    "parse" to fn("any", JsParam("text", "string")),
                    "stringify" to fn("string", JsParam("value", "any")),
                ),
                "Reflect" to iface(
                    "apply" to fn("any"), "construct" to fn("any"),
                    "defineProperty" to fn("boolean"), "deleteProperty" to fn("boolean"),
                    "get" to fn("any"), "set" to fn("boolean"),
                    "has" to fn("boolean"), "ownKeys" to fn("Array"),
                ),
                "Atomics" to iface(
                    "add" to fn("number"), "sub" to fn("number"),
                    "and" to fn("number"), "or" to fn("number"),
                    "xor" to fn("number"), "load" to fn("number"),
                    "store" to fn("number"), "exchange" to fn("number"),
                    "compareExchange" to fn("number"), "wait" to fn("string"),
                    "notify" to fn("number"),
                ),
                "CSSStyleDeclaration" to iface(
                    "cssText" to prop("string"), "length" to prop("number"),
                    "getPropertyValue" to fn("string", JsParam("property", "string")),
                    "setProperty" to fn("void", JsParam("property", "string"), JsParam("value", "string")),
                    "removeProperty" to fn("string", JsParam("property", "string")),
                    "item" to fn("string", JsParam("index", "number")),
                ),
            )

            // ── Variables (globals) ────────────────────────────────────────────

            val varMap = buildVariablesMap()

            // ── Functions (global functions) ───────────────────────────────────

            val fnMap = buildFunctionsMap()

            index.load(ParsedSymbols(interfaces = ifaceMap, variables = varMap, functions = fnMap))
            index.setLoaded(true)
            return index
        }

        private fun buildVariablesMap(): Map<String, JsVariableInfo> {
            val v: (String) -> JsVariableInfo = { type -> JsVariableInfo(type = type) }
            return mapOf(
                // ECMAScript primitive globals
                "undefined" to JsVariableInfo(type = "undefined"),
                "Infinity" to v("number"), "NaN" to v("number"),
                "globalThis" to v("Window"),
                // ECMAScript constructor globals (TYPE$ = typeof companion = newable class)
                "Object" to v("TYPE\$Object"), "Function" to v("TYPE\$Function"),
                "Boolean" to v("TYPE\$Boolean"), "Symbol" to v("TYPE\$Symbol"), "BigInt" to v("TYPE\$BigInt"),
                "Number" to v("TYPE\$Number"), "Math" to v("Math"),
                "Date" to v("TYPE\$Date"), "String" to v("TYPE\$String"), "RegExp" to v("TYPE\$RegExp"),
                "Array" to v("TYPE\$Array"),
                "Map" to v("TYPE\$Map"), "Set" to v("TYPE\$Set"),
                "WeakMap" to v("TYPE\$WeakMap"), "WeakSet" to v("TYPE\$WeakSet"),
                "WeakRef" to v("TYPE\$WeakRef"), "FinalizationRegistry" to v("TYPE\$FinalizationRegistry"),
                "Promise" to v("TYPE\$Promise"), "Proxy" to v("TYPE\$Proxy"),
                "Reflect" to v("Reflect"), "JSON" to v("JSON"), "Atomics" to v("Atomics"),
                "Intl" to v("Intl"),
                // Structured data constructors
                "ArrayBuffer" to v("TYPE\$ArrayBuffer"), "SharedArrayBuffer" to v("TYPE\$SharedArrayBuffer"),
                "DataView" to v("TYPE\$DataView"),
                // Typed array constructors
                "Int8Array" to v("TYPE\$Int8Array"), "Uint8Array" to v("TYPE\$Uint8Array"),
                "Uint8ClampedArray" to v("TYPE\$Uint8ClampedArray"), "Int16Array" to v("TYPE\$Int16Array"),
                "Uint16Array" to v("TYPE\$Uint16Array"), "Int32Array" to v("TYPE\$Int32Array"),
                "Uint32Array" to v("TYPE\$Uint32Array"), "Float32Array" to v("TYPE\$Float32Array"),
                "Float64Array" to v("TYPE\$Float64Array"), "BigInt64Array" to v("TYPE\$BigInt64Array"),
                "BigUint64Array" to v("TYPE\$BigUint64Array"),
                // Error constructors
                "Error" to v("TYPE\$Error"), "AggregateError" to v("TYPE\$AggregateError"),
                "EvalError" to v("TYPE\$EvalError"), "RangeError" to v("TYPE\$RangeError"),
                "ReferenceError" to v("TYPE\$ReferenceError"), "SyntaxError" to v("TYPE\$SyntaxError"),
                "TypeError" to v("TYPE\$TypeError"), "URIError" to v("TYPE\$URIError"),
                // Window / frame
                "window" to v("Window"), "self" to v("Window"),
                "top" to v("Window"), "parent" to v("Window"),
                "frames" to v("Window"), "opener" to v("Window"),
                // DOM globals
                "document" to JsVariableInfo(type = "Document", doc = "The document object."),
                "navigator" to v("Navigator"), "location" to v("Location"),
                "history" to v("History"), "screen" to v("Screen"),
                "console" to v("Console"), "performance" to v("Performance"),
                "crypto" to v("Crypto"),
                // Storage
                "localStorage" to v("Storage"), "sessionStorage" to v("Storage"),
                "indexedDB" to v("IDBFactory"), "caches" to v("CacheStorage"),
                // Web API constructors (TYPE$ = newable class)
                "URL" to v("TYPE\$URL"), "URLSearchParams" to v("TYPE\$URLSearchParams"),
                "Blob" to v("TYPE\$Blob"), "File" to v("TYPE\$File"),
                "FileList" to v("TYPE\$FileList"), "FileReader" to v("TYPE\$FileReader"),
                "FormData" to v("TYPE\$FormData"),
                "Headers" to v("TYPE\$Headers"), "Request" to v("TYPE\$Request"), "Response" to v("TYPE\$Response"),
                "ReadableStream" to v("TYPE\$ReadableStream"), "WritableStream" to v("TYPE\$WritableStream"),
                "TransformStream" to v("TYPE\$TransformStream"),
                "ByteLengthQueuingStrategy" to v("TYPE\$ByteLengthQueuingStrategy"),
                "CountQueuingStrategy" to v("TYPE\$CountQueuingStrategy"),
                "Event" to v("TYPE\$Event"), "EventTarget" to v("TYPE\$EventTarget"),
                "CustomEvent" to v("TYPE\$CustomEvent"),
                "AbortController" to v("TYPE\$AbortController"), "AbortSignal" to v("TYPE\$AbortSignal"),
                "Worker" to v("TYPE\$Worker"), "SharedWorker" to v("TYPE\$SharedWorker"),
                "MessageChannel" to v("TYPE\$MessageChannel"), "MessagePort" to v("TYPE\$MessagePort"),
                "BroadcastChannel" to v("TYPE\$BroadcastChannel"),
                "XMLHttpRequest" to v("TYPE\$XMLHttpRequest"),
                "XMLHttpRequestUpload" to v("TYPE\$XMLHttpRequestUpload"),
                "WebSocket" to v("TYPE\$WebSocket"), "EventSource" to v("TYPE\$EventSource"),
                "MutationObserver" to v("TYPE\$MutationObserver"),
                "IntersectionObserver" to v("TYPE\$IntersectionObserver"),
                "ResizeObserver" to v("TYPE\$ResizeObserver"),
                "PerformanceObserver" to v("TYPE\$PerformanceObserver"),
                "ReportingObserver" to v("TYPE\$ReportingObserver"),
                "TextEncoder" to v("TYPE\$TextEncoder"), "TextDecoder" to v("TYPE\$TextDecoder"),
                "DOMParser" to v("TYPE\$DOMParser"), "XMLSerializer" to v("TYPE\$XMLSerializer"),
                "ImageData" to v("TYPE\$ImageData"), "ImageBitmap" to v("TYPE\$ImageBitmap"),
                "OffscreenCanvas" to v("TYPE\$OffscreenCanvas"), "Path2D" to v("TYPE\$Path2D"),
                "AudioContext" to v("TYPE\$AudioContext"), "OfflineAudioContext" to v("TYPE\$OfflineAudioContext"),
                "MediaStream" to v("TYPE\$MediaStream"), "MediaStreamTrack" to v("TYPE\$MediaStreamTrack"),
                "MediaRecorder" to v("TYPE\$MediaRecorder"),
                "RTCPeerConnection" to v("TYPE\$RTCPeerConnection"),
                "RTCSessionDescription" to v("TYPE\$RTCSessionDescription"),
                "RTCIceCandidate" to v("TYPE\$RTCIceCandidate"),
                "Notification" to v("TYPE\$Notification"),
                "Animation" to v("TYPE\$Animation"), "KeyframeEffect" to v("TYPE\$KeyframeEffect"),
                "IDBFactory" to v("TYPE\$IDBFactory"), "IDBDatabase" to v("TYPE\$IDBDatabase"),
                "IDBObjectStore" to v("TYPE\$IDBObjectStore"), "IDBIndex" to v("TYPE\$IDBIndex"),
                "IDBCursor" to v("TYPE\$IDBCursor"), "IDBTransaction" to v("TYPE\$IDBTransaction"),
                "IDBKeyRange" to v("TYPE\$IDBKeyRange"), "IDBRequest" to v("TYPE\$IDBRequest"),
                "IDBOpenDBRequest" to v("TYPE\$IDBOpenDBRequest"),
                "Cache" to v("TYPE\$Cache"), "CacheStorage" to v("TYPE\$CacheStorage"),
                "ServiceWorker" to v("TYPE\$ServiceWorker"),
                "ServiceWorkerRegistration" to v("TYPE\$ServiceWorkerRegistration"),
                "ServiceWorkerContainer" to v("TYPE\$ServiceWorkerContainer"),
                // Event type constructors
                "UIEvent" to v("TYPE\$UIEvent"), "MouseEvent" to v("TYPE\$MouseEvent"),
                "PointerEvent" to v("TYPE\$PointerEvent"), "KeyboardEvent" to v("TYPE\$KeyboardEvent"),
                "WheelEvent" to v("TYPE\$WheelEvent"), "TouchEvent" to v("TYPE\$TouchEvent"),
                "FocusEvent" to v("TYPE\$FocusEvent"), "InputEvent" to v("TYPE\$InputEvent"),
                "DragEvent" to v("TYPE\$DragEvent"), "ClipboardEvent" to v("TYPE\$ClipboardEvent"),
                "MessageEvent" to v("TYPE\$MessageEvent"), "ErrorEvent" to v("TYPE\$ErrorEvent"),
                "ProgressEvent" to v("TYPE\$ProgressEvent"), "StorageEvent" to v("TYPE\$StorageEvent"),
                "PopStateEvent" to v("TYPE\$PopStateEvent"), "HashChangeEvent" to v("TYPE\$HashChangeEvent"),
                "BeforeUnloadEvent" to v("TYPE\$BeforeUnloadEvent"),
                "PageTransitionEvent" to v("TYPE\$PageTransitionEvent"),
                "AnimationEvent" to v("TYPE\$AnimationEvent"), "TransitionEvent" to v("TYPE\$TransitionEvent"),
                // DOM element type constructors
                "Node" to v("TYPE\$Node"), "Element" to v("TYPE\$Element"),
                "HTMLElement" to v("TYPE\$HTMLElement"), "Document" to v("TYPE\$Document"),
                "DocumentFragment" to v("TYPE\$DocumentFragment"), "ShadowRoot" to v("TYPE\$ShadowRoot"),
                "Text" to v("TYPE\$Text"), "Comment" to v("TYPE\$Comment"),
                "NodeList" to v("TYPE\$NodeList"), "HTMLCollection" to v("TYPE\$HTMLCollection"),
                "Range" to v("TYPE\$Range"), "Selection" to v("TYPE\$Selection"),
                "TreeWalker" to v("TYPE\$TreeWalker"), "NodeFilter" to v("TYPE\$NodeFilter"),
                "MutationRecord" to v("TYPE\$MutationRecord"),
                "IntersectionObserverEntry" to v("TYPE\$IntersectionObserverEntry"),
                "ResizeObserverEntry" to v("TYPE\$ResizeObserverEntry"),
                "DOMRect" to v("TYPE\$DOMRect"), "DOMMatrix" to v("TYPE\$DOMMatrix"),
                "DOMPoint" to v("TYPE\$DOMPoint"),
                // CSS globals (CSS is a singleton namespace, the rest are constructors)
                "CSS" to v("CSS"), "CSSStyleDeclaration" to v("TYPE\$CSSStyleDeclaration"),
                "CSSStyleSheet" to v("TYPE\$CSSStyleSheet"), "StyleSheet" to v("TYPE\$StyleSheet"),
                "MediaQueryList" to v("TYPE\$MediaQueryList"),
                // Canvas/WebGL constructors
                "CanvasRenderingContext2D" to v("TYPE\$CanvasRenderingContext2D"),
                "WebGLRenderingContext" to v("TYPE\$WebGLRenderingContext"),
                "WebGL2RenderingContext" to v("TYPE\$WebGL2RenderingContext"),
            )
        }

        private fun gfn(returns: String, vararg params: JsParam): List<JsMember> =
            listOf(JsMember(kind = "method", returns = returns, params = params.toList()))

        private fun buildFunctionsMap(): Map<String, List<JsMember>> = mapOf(
                // ECMAScript global functions
                "eval" to gfn("any", JsParam("x", "string")),
                "isFinite" to gfn("boolean", JsParam("number", "number")),
                "isNaN" to gfn("boolean", JsParam("number", "number")),
                "parseFloat" to gfn("number", JsParam("string", "string")),
                "parseInt" to gfn("number", JsParam("string", "string"), JsParam("radix", "number", optional = true)),
                "decodeURI" to gfn("string", JsParam("encodedURI", "string")),
                "decodeURIComponent" to gfn("string", JsParam("encodedURIComponent", "string")),
                "encodeURI" to gfn("string", JsParam("uri", "string")),
                "encodeURIComponent" to gfn("string", JsParam("uriComponent", "string")),
                "structuredClone" to gfn("any", JsParam("value", "any")),
                "queueMicrotask" to gfn("void", JsParam("callback", "Function")),
                // Window functions
                "alert" to gfn("void", JsParam("message", "any", optional = true)),
                "confirm" to gfn("boolean", JsParam("message", "string", optional = true)),
                "prompt" to gfn("string", JsParam("message", "string", optional = true)),
                "open" to gfn("Window", JsParam("url", "string", optional = true)),
                "close" to gfn("void"),
                "focus" to gfn("void"), "blur" to gfn("void"), "print" to gfn("void"),
                "scroll" to gfn("void"), "scrollTo" to gfn("void"), "scrollBy" to gfn("void"),
                "getComputedStyle" to gfn("CSSStyleDeclaration", JsParam("elt", "Element")),
                "matchMedia" to gfn("MediaQueryList", JsParam("query", "string")),
                "getSelection" to gfn("Selection"),
                "postMessage" to gfn("void", JsParam("message", "any")),
                "atob" to gfn("string", JsParam("data", "string")),
                "btoa" to gfn("string", JsParam("data", "string")),
                "createImageBitmap" to gfn("Promise", JsParam("image", "ImageBitmapSource")),
                // Timing functions
                "setTimeout" to gfn("number", JsParam("handler", "TimerHandler"), JsParam("timeout", "number", optional = true)),
                "clearTimeout" to gfn("void", JsParam("id", "number", optional = true)),
                "setInterval" to gfn("number", JsParam("handler", "TimerHandler"), JsParam("timeout", "number", optional = true)),
                "clearInterval" to gfn("void", JsParam("id", "number", optional = true)),
                "requestAnimationFrame" to gfn("number", JsParam("callback", "FrameRequestCallback")),
                "cancelAnimationFrame" to gfn("void", JsParam("handle", "number")),
                "requestIdleCallback" to gfn("number", JsParam("callback", "IdleRequestCallback")),
                "cancelIdleCallback" to gfn("void", JsParam("handle", "number")),
                // Fetch
                "fetch" to gfn("Promise", JsParam("input", "RequestInfo"), JsParam("init", "RequestInit", optional = true)),
            )
    }
}
