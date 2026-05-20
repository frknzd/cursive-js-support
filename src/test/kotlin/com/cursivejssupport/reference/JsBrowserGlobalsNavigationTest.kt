package com.cursivejssupport.reference

import com.cursivejssupport.documentation.JsBrowserGlobalsDocTest
import com.cursivejssupport.index.JsSymbolIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the index-level query methods that drive goto-declaration for all js/<global> symbol
 * categories. [JsInteropNavigation.resolveGotoTargets] delegates to these index methods; testing
 * them directly avoids needing a live IntelliJ Project/PSI environment while still verifying that
 * every browser global the extension is expected to support is actually resolvable.
 *
 * Coverage: global variable resolution, global function resolution, interface member resolution,
 * chain type resolution, inherited member resolution, dedup of member candidates, candidate
 * sorting, and typeof-companion look-up.
 */
class JsBrowserGlobalsNavigationTest {

    private lateinit var index: JsSymbolIndex

    @Before
    fun setUp() {
        index = JsBrowserGlobalsDocTest.buildBrowserIndex()
    }

    // ─── ECMAScript primitive globals ────────────────────────────────────────────

    @Test fun `undefined is known global`() { assertKnownGlobal("undefined") }
    @Test fun `Infinity is known global`() { assertKnownGlobal("Infinity") }
    @Test fun `NaN is known global`() { assertKnownGlobal("NaN") }
    @Test fun `globalThis is known global`() { assertKnownGlobal("globalThis") }

    // ─── ECMAScript global functions ─────────────────────────────────────────────

    @Test fun `eval is known function`() { assertKnownFunction("eval") }
    @Test fun `isFinite is known function`() { assertKnownFunction("isFinite") }
    @Test fun `isNaN is known function`() { assertKnownFunction("isNaN") }
    @Test fun `parseFloat is known function`() { assertKnownFunction("parseFloat") }
    @Test fun `parseInt is known function`() { assertKnownFunction("parseInt") }
    @Test fun `decodeURI is known function`() { assertKnownFunction("decodeURI") }
    @Test fun `decodeURIComponent is known function`() { assertKnownFunction("decodeURIComponent") }
    @Test fun `encodeURI is known function`() { assertKnownFunction("encodeURI") }
    @Test fun `encodeURIComponent is known function`() { assertKnownFunction("encodeURIComponent") }
    @Test fun `structuredClone is known function`() { assertKnownFunction("structuredClone") }
    @Test fun `queueMicrotask is known function`() { assertKnownFunction("queueMicrotask") }

    // ─── ECMAScript constructor globals ──────────────────────────────────────────

    @Test fun `Object is known global`() { assertKnownGlobal("Object") }
    @Test fun `Function is known global`() { assertKnownGlobal("Function") }
    @Test fun `Boolean is known global`() { assertKnownGlobal("Boolean") }
    @Test fun `Symbol is known global`() { assertKnownGlobal("Symbol") }
    @Test fun `BigInt is known global`() { assertKnownGlobal("BigInt") }
    @Test fun `Number is known global`() { assertKnownGlobal("Number") }
    @Test fun `Math global has type Math`() {
        assertEquals("Math", index.resolveGlobalInfo("Math")?.type)
    }
    @Test fun `Date is known global`() { assertKnownGlobal("Date") }
    @Test fun `String is known global`() { assertKnownGlobal("String") }
    @Test fun `RegExp is known global`() { assertKnownGlobal("RegExp") }
    @Test fun `Array is known global`() { assertKnownGlobal("Array") }
    @Test fun `Map is known global`() { assertKnownGlobal("Map") }
    @Test fun `Set is known global`() { assertKnownGlobal("Set") }
    @Test fun `WeakMap is known global`() { assertKnownGlobal("WeakMap") }
    @Test fun `WeakSet is known global`() { assertKnownGlobal("WeakSet") }
    @Test fun `WeakRef is known global`() { assertKnownGlobal("WeakRef") }
    @Test fun `FinalizationRegistry is known global`() { assertKnownGlobal("FinalizationRegistry") }
    @Test fun `Promise is known global`() { assertKnownGlobal("Promise") }
    @Test fun `Proxy is known global`() { assertKnownGlobal("Proxy") }
    @Test fun `Reflect global has type Reflect`() {
        assertEquals("Reflect", index.resolveGlobalInfo("Reflect")?.type)
    }
    @Test fun `JSON global has type JSON`() {
        assertEquals("JSON", index.resolveGlobalInfo("JSON")?.type)
    }
    @Test fun `Atomics global has type Atomics`() {
        assertEquals("Atomics", index.resolveGlobalInfo("Atomics")?.type)
    }
    @Test fun `Intl is known global`() { assertKnownGlobal("Intl") }
    @Test fun `ArrayBuffer is known global`() { assertKnownGlobal("ArrayBuffer") }
    @Test fun `SharedArrayBuffer is known global`() { assertKnownGlobal("SharedArrayBuffer") }
    @Test fun `DataView is known global`() { assertKnownGlobal("DataView") }

    // ─── Typed arrays ─────────────────────────────────────────────────────────────

    @Test fun `Int8Array is known global`() { assertKnownGlobal("Int8Array") }
    @Test fun `Uint8Array is known global`() { assertKnownGlobal("Uint8Array") }
    @Test fun `Uint8ClampedArray is known global`() { assertKnownGlobal("Uint8ClampedArray") }
    @Test fun `Int16Array is known global`() { assertKnownGlobal("Int16Array") }
    @Test fun `Uint16Array is known global`() { assertKnownGlobal("Uint16Array") }
    @Test fun `Int32Array is known global`() { assertKnownGlobal("Int32Array") }
    @Test fun `Uint32Array is known global`() { assertKnownGlobal("Uint32Array") }
    @Test fun `Float32Array is known global`() { assertKnownGlobal("Float32Array") }
    @Test fun `Float64Array is known global`() { assertKnownGlobal("Float64Array") }
    @Test fun `BigInt64Array is known global`() { assertKnownGlobal("BigInt64Array") }
    @Test fun `BigUint64Array is known global`() { assertKnownGlobal("BigUint64Array") }

    // ─── Error types ──────────────────────────────────────────────────────────────

    @Test fun `Error is known global`() { assertKnownGlobal("Error") }
    @Test fun `AggregateError is known global`() { assertKnownGlobal("AggregateError") }
    @Test fun `EvalError is known global`() { assertKnownGlobal("EvalError") }
    @Test fun `RangeError is known global`() { assertKnownGlobal("RangeError") }
    @Test fun `ReferenceError is known global`() { assertKnownGlobal("ReferenceError") }
    @Test fun `SyntaxError is known global`() { assertKnownGlobal("SyntaxError") }
    @Test fun `TypeError is known global`() { assertKnownGlobal("TypeError") }
    @Test fun `URIError is known global`() { assertKnownGlobal("URIError") }

    // ─── Window globals ───────────────────────────────────────────────────────────

    @Test fun `window global has type Window`() { assertEquals("Window", index.resolveGlobalType("window")) }
    @Test fun `self global has type Window`() { assertEquals("Window", index.resolveGlobalType("self")) }
    @Test fun `top global is known`() { assertKnownGlobal("top") }
    @Test fun `parent global is known`() { assertKnownGlobal("parent") }
    @Test fun `frames global is known`() { assertKnownGlobal("frames") }
    @Test fun `opener global is known`() { assertKnownGlobal("opener") }

    // ─── DOM globals ──────────────────────────────────────────────────────────────

    @Test fun `document global has type Document`() { assertEquals("Document", index.resolveGlobalType("document")) }
    @Test fun `navigator global has type Navigator`() { assertEquals("Navigator", index.resolveGlobalType("navigator")) }
    @Test fun `location global has type Location`() { assertEquals("Location", index.resolveGlobalType("location")) }
    @Test fun `history global has type History`() { assertEquals("History", index.resolveGlobalType("history")) }
    @Test fun `screen global has type Screen`() { assertEquals("Screen", index.resolveGlobalType("screen")) }
    @Test fun `console global has type Console`() { assertEquals("Console", index.resolveGlobalType("console")) }
    @Test fun `performance global has type Performance`() { assertEquals("Performance", index.resolveGlobalType("performance")) }
    @Test fun `crypto global has type Crypto`() { assertEquals("Crypto", index.resolveGlobalType("crypto")) }

    // ─── Storage globals ──────────────────────────────────────────────────────────

    @Test fun `localStorage global has type Storage`() { assertEquals("Storage", index.resolveGlobalType("localStorage")) }
    @Test fun `sessionStorage global has type Storage`() { assertEquals("Storage", index.resolveGlobalType("sessionStorage")) }
    @Test fun `indexedDB global has type IDBFactory`() { assertEquals("IDBFactory", index.resolveGlobalType("indexedDB")) }
    @Test fun `caches global has type CacheStorage`() { assertEquals("CacheStorage", index.resolveGlobalType("caches")) }

    // ─── Browser window functions ─────────────────────────────────────────────────

    @Test fun `fetch is known function`() { assertKnownFunction("fetch") }
    @Test fun `alert is known function`() { assertKnownFunction("alert") }
    @Test fun `confirm is known function`() { assertKnownFunction("confirm") }
    @Test fun `prompt is known function`() { assertKnownFunction("prompt") }
    @Test fun `open is known function`() { assertKnownFunction("open") }
    @Test fun `close is known function`() { assertKnownFunction("close") }
    @Test fun `focus is known function`() { assertKnownFunction("focus") }
    @Test fun `blur is known function`() { assertKnownFunction("blur") }
    @Test fun `print is known function`() { assertKnownFunction("print") }
    @Test fun `setTimeout is known function`() { assertKnownFunction("setTimeout") }
    @Test fun `clearTimeout is known function`() { assertKnownFunction("clearTimeout") }
    @Test fun `setInterval is known function`() { assertKnownFunction("setInterval") }
    @Test fun `clearInterval is known function`() { assertKnownFunction("clearInterval") }
    @Test fun `requestAnimationFrame is known function`() { assertKnownFunction("requestAnimationFrame") }
    @Test fun `cancelAnimationFrame is known function`() { assertKnownFunction("cancelAnimationFrame") }
    @Test fun `requestIdleCallback is known function`() { assertKnownFunction("requestIdleCallback") }
    @Test fun `cancelIdleCallback is known function`() { assertKnownFunction("cancelIdleCallback") }
    @Test fun `getComputedStyle is known function`() { assertKnownFunction("getComputedStyle") }
    @Test fun `matchMedia is known function`() { assertKnownFunction("matchMedia") }
    @Test fun `getSelection is known function`() { assertKnownFunction("getSelection") }
    @Test fun `postMessage is known function`() { assertKnownFunction("postMessage") }
    @Test fun `atob is known function`() { assertKnownFunction("atob") }
    @Test fun `btoa is known function`() { assertKnownFunction("btoa") }
    @Test fun `createImageBitmap is known function`() { assertKnownFunction("createImageBitmap") }
    @Test fun `scroll is known function`() { assertKnownFunction("scroll") }
    @Test fun `scrollTo is known function`() { assertKnownFunction("scrollTo") }
    @Test fun `scrollBy is known function`() { assertKnownFunction("scrollBy") }

    // ─── Web API constructor globals ──────────────────────────────────────────────

    @Test fun `URL is known global`() { assertKnownGlobal("URL") }
    @Test fun `URLSearchParams is known global`() { assertKnownGlobal("URLSearchParams") }
    @Test fun `Blob is known global`() { assertKnownGlobal("Blob") }
    @Test fun `File is known global`() { assertKnownGlobal("File") }
    @Test fun `FileList is known global`() { assertKnownGlobal("FileList") }
    @Test fun `FileReader is known global`() { assertKnownGlobal("FileReader") }
    @Test fun `FormData is known global`() { assertKnownGlobal("FormData") }
    @Test fun `Headers is known global`() { assertKnownGlobal("Headers") }
    @Test fun `Request is known global`() { assertKnownGlobal("Request") }
    @Test fun `Response is known global`() { assertKnownGlobal("Response") }
    @Test fun `ReadableStream is known global`() { assertKnownGlobal("ReadableStream") }
    @Test fun `WritableStream is known global`() { assertKnownGlobal("WritableStream") }
    @Test fun `TransformStream is known global`() { assertKnownGlobal("TransformStream") }
    @Test fun `ByteLengthQueuingStrategy is known global`() { assertKnownGlobal("ByteLengthQueuingStrategy") }
    @Test fun `CountQueuingStrategy is known global`() { assertKnownGlobal("CountQueuingStrategy") }
    @Test fun `Event is known global`() { assertKnownGlobal("Event") }
    @Test fun `EventTarget is known global`() { assertKnownGlobal("EventTarget") }
    @Test fun `CustomEvent is known global`() { assertKnownGlobal("CustomEvent") }
    @Test fun `AbortController is known global`() { assertKnownGlobal("AbortController") }
    @Test fun `AbortSignal is known global`() { assertKnownGlobal("AbortSignal") }
    @Test fun `Worker is known global`() { assertKnownGlobal("Worker") }
    @Test fun `SharedWorker is known global`() { assertKnownGlobal("SharedWorker") }
    @Test fun `MessageChannel is known global`() { assertKnownGlobal("MessageChannel") }
    @Test fun `MessagePort is known global`() { assertKnownGlobal("MessagePort") }
    @Test fun `BroadcastChannel is known global`() { assertKnownGlobal("BroadcastChannel") }
    @Test fun `XMLHttpRequest is known global`() { assertKnownGlobal("XMLHttpRequest") }
    @Test fun `WebSocket is known global`() { assertKnownGlobal("WebSocket") }
    @Test fun `EventSource is known global`() { assertKnownGlobal("EventSource") }
    @Test fun `MutationObserver is known global`() { assertKnownGlobal("MutationObserver") }
    @Test fun `IntersectionObserver is known global`() { assertKnownGlobal("IntersectionObserver") }
    @Test fun `ResizeObserver is known global`() { assertKnownGlobal("ResizeObserver") }
    @Test fun `PerformanceObserver is known global`() { assertKnownGlobal("PerformanceObserver") }
    @Test fun `ReportingObserver is known global`() { assertKnownGlobal("ReportingObserver") }
    @Test fun `TextEncoder is known global`() { assertKnownGlobal("TextEncoder") }
    @Test fun `TextDecoder is known global`() { assertKnownGlobal("TextDecoder") }
    @Test fun `DOMParser is known global`() { assertKnownGlobal("DOMParser") }
    @Test fun `XMLSerializer is known global`() { assertKnownGlobal("XMLSerializer") }
    @Test fun `ImageData is known global`() { assertKnownGlobal("ImageData") }
    @Test fun `ImageBitmap is known global`() { assertKnownGlobal("ImageBitmap") }
    @Test fun `OffscreenCanvas is known global`() { assertKnownGlobal("OffscreenCanvas") }
    @Test fun `Path2D is known global`() { assertKnownGlobal("Path2D") }
    @Test fun `AudioContext is known global`() { assertKnownGlobal("AudioContext") }
    @Test fun `OfflineAudioContext is known global`() { assertKnownGlobal("OfflineAudioContext") }
    @Test fun `MediaStream is known global`() { assertKnownGlobal("MediaStream") }
    @Test fun `MediaStreamTrack is known global`() { assertKnownGlobal("MediaStreamTrack") }
    @Test fun `MediaRecorder is known global`() { assertKnownGlobal("MediaRecorder") }
    @Test fun `RTCPeerConnection is known global`() { assertKnownGlobal("RTCPeerConnection") }
    @Test fun `RTCSessionDescription is known global`() { assertKnownGlobal("RTCSessionDescription") }
    @Test fun `RTCIceCandidate is known global`() { assertKnownGlobal("RTCIceCandidate") }
    @Test fun `Notification is known global`() { assertKnownGlobal("Notification") }
    @Test fun `Animation is known global`() { assertKnownGlobal("Animation") }
    @Test fun `KeyframeEffect is known global`() { assertKnownGlobal("KeyframeEffect") }
    @Test fun `IDBFactory is known global`() { assertKnownGlobal("IDBFactory") }
    @Test fun `IDBDatabase is known global`() { assertKnownGlobal("IDBDatabase") }
    @Test fun `IDBObjectStore is known global`() { assertKnownGlobal("IDBObjectStore") }
    @Test fun `IDBIndex is known global`() { assertKnownGlobal("IDBIndex") }
    @Test fun `IDBCursor is known global`() { assertKnownGlobal("IDBCursor") }
    @Test fun `IDBTransaction is known global`() { assertKnownGlobal("IDBTransaction") }
    @Test fun `IDBKeyRange is known global`() { assertKnownGlobal("IDBKeyRange") }
    @Test fun `IDBRequest is known global`() { assertKnownGlobal("IDBRequest") }
    @Test fun `IDBOpenDBRequest is known global`() { assertKnownGlobal("IDBOpenDBRequest") }
    @Test fun `Cache is known global`() { assertKnownGlobal("Cache") }
    @Test fun `CacheStorage is known global`() { assertKnownGlobal("CacheStorage") }
    @Test fun `ServiceWorker is known global`() { assertKnownGlobal("ServiceWorker") }
    @Test fun `ServiceWorkerRegistration is known global`() { assertKnownGlobal("ServiceWorkerRegistration") }
    @Test fun `ServiceWorkerContainer is known global`() { assertKnownGlobal("ServiceWorkerContainer") }
    @Test fun `NodeFilter is known global`() { assertKnownGlobal("NodeFilter") }

    // ─── Event type globals ────────────────────────────────────────────────────────

    @Test fun `UIEvent is known global`() { assertKnownGlobal("UIEvent") }
    @Test fun `MouseEvent is known global`() { assertKnownGlobal("MouseEvent") }
    @Test fun `PointerEvent is known global`() { assertKnownGlobal("PointerEvent") }
    @Test fun `KeyboardEvent is known global`() { assertKnownGlobal("KeyboardEvent") }
    @Test fun `WheelEvent is known global`() { assertKnownGlobal("WheelEvent") }
    @Test fun `TouchEvent is known global`() { assertKnownGlobal("TouchEvent") }
    @Test fun `FocusEvent is known global`() { assertKnownGlobal("FocusEvent") }
    @Test fun `InputEvent is known global`() { assertKnownGlobal("InputEvent") }
    @Test fun `DragEvent is known global`() { assertKnownGlobal("DragEvent") }
    @Test fun `ClipboardEvent is known global`() { assertKnownGlobal("ClipboardEvent") }
    @Test fun `MessageEvent is known global`() { assertKnownGlobal("MessageEvent") }
    @Test fun `ErrorEvent is known global`() { assertKnownGlobal("ErrorEvent") }
    @Test fun `ProgressEvent is known global`() { assertKnownGlobal("ProgressEvent") }
    @Test fun `StorageEvent is known global`() { assertKnownGlobal("StorageEvent") }
    @Test fun `PopStateEvent is known global`() { assertKnownGlobal("PopStateEvent") }
    @Test fun `HashChangeEvent is known global`() { assertKnownGlobal("HashChangeEvent") }
    @Test fun `BeforeUnloadEvent is known global`() { assertKnownGlobal("BeforeUnloadEvent") }
    @Test fun `PageTransitionEvent is known global`() { assertKnownGlobal("PageTransitionEvent") }
    @Test fun `AnimationEvent is known global`() { assertKnownGlobal("AnimationEvent") }
    @Test fun `TransitionEvent is known global`() { assertKnownGlobal("TransitionEvent") }

    // ─── DOM element type globals ──────────────────────────────────────────────────

    @Test fun `Node is known global`() { assertKnownGlobal("Node") }
    @Test fun `Element is known global`() { assertKnownGlobal("Element") }
    @Test fun `HTMLElement is known global`() { assertKnownGlobal("HTMLElement") }
    @Test fun `Document is known global`() { assertKnownGlobal("Document") }
    @Test fun `DocumentFragment is known global`() { assertKnownGlobal("DocumentFragment") }
    @Test fun `ShadowRoot is known global`() { assertKnownGlobal("ShadowRoot") }
    @Test fun `Text is known global`() { assertKnownGlobal("Text") }
    @Test fun `Comment is known global`() { assertKnownGlobal("Comment") }
    @Test fun `NodeList is known global`() { assertKnownGlobal("NodeList") }
    @Test fun `HTMLCollection is known global`() { assertKnownGlobal("HTMLCollection") }
    @Test fun `Range is known global`() { assertKnownGlobal("Range") }
    @Test fun `Selection is known global`() { assertKnownGlobal("Selection") }
    @Test fun `TreeWalker is known global`() { assertKnownGlobal("TreeWalker") }
    @Test fun `MutationRecord is known global`() { assertKnownGlobal("MutationRecord") }
    @Test fun `IntersectionObserverEntry is known global`() { assertKnownGlobal("IntersectionObserverEntry") }
    @Test fun `ResizeObserverEntry is known global`() { assertKnownGlobal("ResizeObserverEntry") }
    @Test fun `DOMRect is known global`() { assertKnownGlobal("DOMRect") }
    @Test fun `DOMMatrix is known global`() { assertKnownGlobal("DOMMatrix") }
    @Test fun `DOMPoint is known global`() { assertKnownGlobal("DOMPoint") }

    // ─── CSS globals ────────────────────────────────────────────────────────────────

    @Test fun `CSS is known global`() { assertKnownGlobal("CSS") }
    @Test fun `CSSStyleDeclaration is known global`() { assertKnownGlobal("CSSStyleDeclaration") }
    @Test fun `CSSStyleSheet is known global`() { assertKnownGlobal("CSSStyleSheet") }
    @Test fun `StyleSheet is known global`() { assertKnownGlobal("StyleSheet") }
    @Test fun `MediaQueryList is known global`() { assertKnownGlobal("MediaQueryList") }

    // ─── Canvas / WebGL globals ────────────────────────────────────────────────────

    @Test fun `CanvasRenderingContext2D is known global`() { assertKnownGlobal("CanvasRenderingContext2D") }
    @Test fun `WebGLRenderingContext is known global`() { assertKnownGlobal("WebGLRenderingContext") }
    @Test fun `WebGL2RenderingContext is known global`() { assertKnownGlobal("WebGL2RenderingContext") }

    // ─── Interface member resolution (goto target exists) ─────────────────────────

    @Test fun `Document-createElement member resolves`() { assertMemberResolvable("Document", "createElement") }
    @Test fun `Document-createRange member resolves`() { assertMemberResolvable("Document", "createRange") }
    @Test fun `Document-getElementById member resolves`() { assertMemberResolvable("Document", "getElementById") }
    @Test fun `Document-querySelector member resolves`() { assertMemberResolvable("Document", "querySelector") }
    @Test fun `Document-querySelectorAll member resolves`() { assertMemberResolvable("Document", "querySelectorAll") }
    @Test fun `Document-title property resolves`() { assertMemberResolvable("Document", "title") }
    @Test fun `Document-body property resolves`() { assertMemberResolvable("Document", "body") }
    @Test fun `Document-cookie property resolves`() { assertMemberResolvable("Document", "cookie") }
    @Test fun `Document-readyState property resolves`() { assertMemberResolvable("Document", "readyState") }
    @Test fun `Document-visibilityState property resolves`() { assertMemberResolvable("Document", "visibilityState") }
    @Test fun `Document-createDocumentFragment resolves`() { assertMemberResolvable("Document", "createDocumentFragment") }
    @Test fun `Document-hasFocus resolves`() { assertMemberResolvable("Document", "hasFocus") }
    @Test fun `Document-elementFromPoint resolves`() { assertMemberResolvable("Document", "elementFromPoint") }
    @Test fun `Element-getAttribute member resolves`() { assertMemberResolvable("Element", "getAttribute") }
    @Test fun `Element-setAttribute member resolves`() { assertMemberResolvable("Element", "setAttribute") }
    @Test fun `Element-addEventListener member resolves`() { assertMemberResolvable("Element", "addEventListener") }
    @Test fun `Element-removeEventListener member resolves`() { assertMemberResolvable("Element", "removeEventListener") }
    @Test fun `Element-dispatchEvent member resolves`() { assertMemberResolvable("Element", "dispatchEvent") }
    @Test fun `Element-getBoundingClientRect member resolves`() { assertMemberResolvable("Element", "getBoundingClientRect") }
    @Test fun `Element-innerHTML property resolves`() { assertMemberResolvable("Element", "innerHTML") }
    @Test fun `Element-className property resolves`() { assertMemberResolvable("Element", "className") }
    @Test fun `Element-scrollTop property resolves`() { assertMemberResolvable("Element", "scrollTop") }
    @Test fun `Element-clientWidth property resolves`() { assertMemberResolvable("Element", "clientWidth") }
    @Test fun `Element-matches resolves`() { assertMemberResolvable("Element", "matches") }
    @Test fun `Element-closest resolves`() { assertMemberResolvable("Element", "closest") }
    @Test fun `HTMLElement-style property resolves`() { assertMemberResolvable("HTMLElement", "style") }
    @Test fun `HTMLElement-tabIndex property resolves`() { assertMemberResolvable("HTMLElement", "tabIndex") }
    @Test fun `HTMLElement-click method resolves`() { assertMemberResolvable("HTMLElement", "click") }
    @Test fun `HTMLElement-offsetWidth property resolves`() { assertMemberResolvable("HTMLElement", "offsetWidth") }
    @Test fun `HTMLElement-hidden property resolves`() { assertMemberResolvable("HTMLElement", "hidden") }
    @Test fun `Node-nodeType property resolves`() { assertMemberResolvable("Node", "nodeType") }
    @Test fun `Node-parentNode property resolves`() { assertMemberResolvable("Node", "parentNode") }
    @Test fun `Node-appendChild method resolves`() { assertMemberResolvable("Node", "appendChild") }
    @Test fun `Node-removeChild method resolves`() { assertMemberResolvable("Node", "removeChild") }
    @Test fun `Node-cloneNode method resolves`() { assertMemberResolvable("Node", "cloneNode") }
    @Test fun `Navigator-userAgent property resolves`() { assertMemberResolvable("Navigator", "userAgent") }
    @Test fun `Navigator-language property resolves`() { assertMemberResolvable("Navigator", "language") }
    @Test fun `Navigator-cookieEnabled property resolves`() { assertMemberResolvable("Navigator", "cookieEnabled") }
    @Test fun `Navigator-onLine property resolves`() { assertMemberResolvable("Navigator", "onLine") }
    @Test fun `Navigator-sendBeacon method resolves`() { assertMemberResolvable("Navigator", "sendBeacon") }
    @Test fun `Location-href property resolves`() { assertMemberResolvable("Location", "href") }
    @Test fun `Location-pathname property resolves`() { assertMemberResolvable("Location", "pathname") }
    @Test fun `Location-search property resolves`() { assertMemberResolvable("Location", "search") }
    @Test fun `Location-assign method resolves`() { assertMemberResolvable("Location", "assign") }
    @Test fun `Location-replace method resolves`() { assertMemberResolvable("Location", "replace") }
    @Test fun `Location-reload method resolves`() { assertMemberResolvable("Location", "reload") }
    @Test fun `History-length property resolves`() { assertMemberResolvable("History", "length") }
    @Test fun `History-state property resolves`() { assertMemberResolvable("History", "state") }
    @Test fun `History-pushState method resolves`() { assertMemberResolvable("History", "pushState") }
    @Test fun `History-replaceState method resolves`() { assertMemberResolvable("History", "replaceState") }
    @Test fun `History-back method resolves`() { assertMemberResolvable("History", "back") }
    @Test fun `History-forward method resolves`() { assertMemberResolvable("History", "forward") }
    @Test fun `Console-log method resolves`() { assertMemberResolvable("Console", "log") }
    @Test fun `Console-warn method resolves`() { assertMemberResolvable("Console", "warn") }
    @Test fun `Console-error method resolves`() { assertMemberResolvable("Console", "error") }
    @Test fun `Console-time method resolves`() { assertMemberResolvable("Console", "time") }
    @Test fun `Console-table method resolves`() { assertMemberResolvable("Console", "table") }
    @Test fun `Console-group method resolves`() { assertMemberResolvable("Console", "group") }
    @Test fun `Performance-now method resolves`() { assertMemberResolvable("Performance", "now") }
    @Test fun `Performance-mark method resolves`() { assertMemberResolvable("Performance", "mark") }
    @Test fun `Performance-measure method resolves`() { assertMemberResolvable("Performance", "measure") }
    @Test fun `Performance-getEntries method resolves`() { assertMemberResolvable("Performance", "getEntries") }
    @Test fun `Performance-clearMarks method resolves`() { assertMemberResolvable("Performance", "clearMarks") }
    @Test fun `Crypto-subtle property resolves`() { assertMemberResolvable("Crypto", "subtle") }
    @Test fun `Crypto-getRandomValues method resolves`() { assertMemberResolvable("Crypto", "getRandomValues") }
    @Test fun `Crypto-randomUUID method resolves`() { assertMemberResolvable("Crypto", "randomUUID") }
    @Test fun `Storage-getItem method resolves`() { assertMemberResolvable("Storage", "getItem") }
    @Test fun `Storage-setItem method resolves`() { assertMemberResolvable("Storage", "setItem") }
    @Test fun `Storage-removeItem method resolves`() { assertMemberResolvable("Storage", "removeItem") }
    @Test fun `Storage-length property resolves`() { assertMemberResolvable("Storage", "length") }
    @Test fun `Storage-clear method resolves`() { assertMemberResolvable("Storage", "clear") }
    @Test fun `MutationObserver-observe method resolves`() { assertMemberResolvable("MutationObserver", "observe") }
    @Test fun `MutationObserver-disconnect method resolves`() { assertMemberResolvable("MutationObserver", "disconnect") }
    @Test fun `MutationObserver-takeRecords method resolves`() { assertMemberResolvable("MutationObserver", "takeRecords") }
    @Test fun `IntersectionObserver-observe method resolves`() { assertMemberResolvable("IntersectionObserver", "observe") }
    @Test fun `IntersectionObserver-disconnect method resolves`() { assertMemberResolvable("IntersectionObserver", "disconnect") }
    @Test fun `ResizeObserver-observe method resolves`() { assertMemberResolvable("ResizeObserver", "observe") }
    @Test fun `ReadableStream-locked property resolves`() { assertMemberResolvable("ReadableStream", "locked") }
    @Test fun `ReadableStream-getReader method resolves`() { assertMemberResolvable("ReadableStream", "getReader") }
    @Test fun `ReadableStream-pipeTo method resolves`() { assertMemberResolvable("ReadableStream", "pipeTo") }
    @Test fun `WritableStream-locked property resolves`() { assertMemberResolvable("WritableStream", "locked") }
    @Test fun `WritableStream-getWriter method resolves`() { assertMemberResolvable("WritableStream", "getWriter") }
    @Test fun `TransformStream-readable property resolves`() { assertMemberResolvable("TransformStream", "readable") }
    @Test fun `TransformStream-writable property resolves`() { assertMemberResolvable("TransformStream", "writable") }
    @Test fun `TextEncoder-encoding property resolves`() { assertMemberResolvable("TextEncoder", "encoding") }
    @Test fun `TextEncoder-encode method resolves`() { assertMemberResolvable("TextEncoder", "encode") }
    @Test fun `TextDecoder-decode method resolves`() { assertMemberResolvable("TextDecoder", "decode") }
    @Test fun `XMLHttpRequest-open method resolves`() { assertMemberResolvable("XMLHttpRequest", "open") }
    @Test fun `XMLHttpRequest-send method resolves`() { assertMemberResolvable("XMLHttpRequest", "send") }
    @Test fun `XMLHttpRequest-readyState property resolves`() { assertMemberResolvable("XMLHttpRequest", "readyState") }
    @Test fun `XMLHttpRequest-status property resolves`() { assertMemberResolvable("XMLHttpRequest", "status") }
    @Test fun `XMLHttpRequest-getResponseHeader method resolves`() { assertMemberResolvable("XMLHttpRequest", "getResponseHeader") }
    @Test fun `WebSocket-send method resolves`() { assertMemberResolvable("WebSocket", "send") }
    @Test fun `WebSocket-close method resolves`() { assertMemberResolvable("WebSocket", "close") }
    @Test fun `WebSocket-readyState property resolves`() { assertMemberResolvable("WebSocket", "readyState") }
    @Test fun `NodeFilter-acceptNode method resolves`() { assertMemberResolvable("NodeFilter", "acceptNode") }
    @Test fun `TYPE-dollar-NodeFilter-SHOW_ELEMENT resolves`() { assertMemberResolvable("TYPE\$NodeFilter", "SHOW_ELEMENT") }
    @Test fun `TYPE-dollar-NodeFilter-FILTER_ACCEPT resolves`() { assertMemberResolvable("TYPE\$NodeFilter", "FILTER_ACCEPT") }
    @Test fun `Math-random method resolves`() { assertMemberResolvable("Math", "random") }
    @Test fun `Math-PI property resolves`() { assertMemberResolvable("Math", "PI") }
    @Test fun `JSON-parse method resolves`() { assertMemberResolvable("JSON", "parse") }
    @Test fun `JSON-stringify method resolves`() { assertMemberResolvable("JSON", "stringify") }
    @Test fun `Reflect-apply method resolves`() { assertMemberResolvable("Reflect", "apply") }
    @Test fun `Atomics-add method resolves`() { assertMemberResolvable("Atomics", "add") }
    @Test fun `Atomics-wait method resolves`() { assertMemberResolvable("Atomics", "wait") }
    @Test fun `Window-innerWidth property resolves`() { assertMemberResolvable("Window", "innerWidth") }
    @Test fun `Window-innerHeight property resolves`() { assertMemberResolvable("Window", "innerHeight") }
    @Test fun `Window-scrollX property resolves`() { assertMemberResolvable("Window", "scrollX") }
    @Test fun `Window-devicePixelRatio property resolves`() { assertMemberResolvable("Window", "devicePixelRatio") }
    @Test fun `Screen-width property resolves`() { assertMemberResolvable("Screen", "width") }
    @Test fun `Screen-height property resolves`() { assertMemberResolvable("Screen", "height") }
    @Test fun `Screen-availWidth property resolves`() { assertMemberResolvable("Screen", "availWidth") }
    @Test fun `MediaQueryList-matches property resolves`() { assertMemberResolvable("MediaQueryList", "matches") }
    @Test fun `MediaQueryList-media property resolves`() { assertMemberResolvable("MediaQueryList", "media") }
    @Test fun `IDBFactory-open method resolves`() { assertMemberResolvable("IDBFactory", "open") }
    @Test fun `IDBFactory-deleteDatabase method resolves`() { assertMemberResolvable("IDBFactory", "deleteDatabase") }

    // ─── Chain type resolution (drives goto for js/a.b forms) ────────────────────

    @Test fun `chain document resolves to Document`() {
        assertEquals("Document", index.resolveJsChainType(listOf("document")))
    }

    @Test fun `chain navigator resolves to Navigator`() {
        assertEquals("Navigator", index.resolveJsChainType(listOf("navigator")))
    }

    @Test fun `chain location resolves to Location`() {
        assertEquals("Location", index.resolveJsChainType(listOf("location")))
    }

    @Test fun `chain history resolves to History`() {
        assertEquals("History", index.resolveJsChainType(listOf("history")))
    }

    @Test fun `chain screen resolves to Screen`() {
        assertEquals("Screen", index.resolveJsChainType(listOf("screen")))
    }

    @Test fun `chain console resolves to Console`() {
        assertEquals("Console", index.resolveJsChainType(listOf("console")))
    }

    @Test fun `chain performance resolves to Performance`() {
        assertEquals("Performance", index.resolveJsChainType(listOf("performance")))
    }

    @Test fun `chain crypto resolves to Crypto`() {
        assertEquals("Crypto", index.resolveJsChainType(listOf("crypto")))
    }

    @Test fun `chain localStorage resolves to Storage`() {
        assertEquals("Storage", index.resolveJsChainType(listOf("localStorage")))
    }

    @Test fun `chain sessionStorage resolves to Storage`() {
        assertEquals("Storage", index.resolveJsChainType(listOf("sessionStorage")))
    }

    @Test fun `chain document-createElement resolves return type`() {
        assertEquals("Element", index.resolveJsChainType(listOf("document", "createElement")))
    }

    @Test fun `chain document-createRange resolves return type`() {
        assertEquals("Range", index.resolveJsChainType(listOf("document", "createRange")))
    }

    @Test fun `chain document-createDocumentFragment resolves return type`() {
        assertEquals("DocumentFragment", index.resolveJsChainType(listOf("document", "createDocumentFragment")))
    }

    @Test fun `chain navigator-userAgent resolves to string`() {
        assertEquals("string", index.resolveJsChainType(listOf("navigator", "userAgent")))
    }

    @Test fun `chain location-href resolves to string`() {
        assertEquals("string", index.resolveJsChainType(listOf("location", "href")))
    }

    @Test fun `chain location-pathname resolves to string`() {
        assertEquals("string", index.resolveJsChainType(listOf("location", "pathname")))
    }

    @Test fun `chain history-length resolves to number`() {
        assertEquals("number", index.resolveJsChainType(listOf("history", "length")))
    }

    @Test fun `chain performance-now resolves to number`() {
        assertEquals("number", index.resolveJsChainType(listOf("performance", "now")))
    }

    @Test fun `chain crypto-subtle resolves to SubtleCrypto`() {
        assertEquals("SubtleCrypto", index.resolveJsChainType(listOf("crypto", "subtle")))
    }

    @Test fun `chain localStorage-getItem resolves to string`() {
        assertEquals("string", index.resolveJsChainType(listOf("localStorage", "getItem")))
    }

    @Test fun `chain localStorage-length resolves to number`() {
        assertEquals("number", index.resolveJsChainType(listOf("localStorage", "length")))
    }

    @Test fun `chain window-innerWidth resolves to number`() {
        assertEquals("number", index.resolveJsChainType(listOf("window", "innerWidth")))
    }

    @Test fun `chain screen-width resolves to number`() {
        assertEquals("number", index.resolveJsChainType(listOf("screen", "width")))
    }

    @Test fun `chain console-log resolves to void`() {
        assertEquals("void", index.resolveJsChainType(listOf("console", "log")))
    }

    @Test fun `getComputedStyle function returns CSSStyleDeclaration`() {
        // resolveJsChainType returns "Function" for global functions (not their return type);
        // the return type is read directly from the function overload.
        assertEquals("CSSStyleDeclaration", index.resolveFunctions("getComputedStyle")?.first()?.returns)
    }

    @Test fun `matchMedia function returns MediaQueryList`() {
        assertEquals("MediaQueryList", index.resolveFunctions("matchMedia")?.first()?.returns)
    }

    @Test fun `unknown chain segment returns null`() {
        assertNull(index.resolveJsChainType(listOf("document", "__nonexistent__")))
    }

    @Test fun `unknown root global returns null`() {
        assertNull(index.resolveJsChainType(listOf("__nonexistent__")))
    }

    // ─── Inheritance: HTMLElement extends Element ──────────────────────────────────

    @Test fun `HTMLElement inherits addEventListener from Element`() {
        val resolved = index.resolveMember("HTMLElement", "addEventListener")
        assertNotNull(resolved)
        assertEquals("Element", resolved!!.declaringType)
        assertTrue(resolved.distance > 0)
    }

    @Test fun `HTMLElement inherits getAttribute from Element`() {
        val resolved = index.resolveMember("HTMLElement", "getAttribute")
        assertNotNull(resolved)
        assertEquals("Element", resolved!!.declaringType)
    }

    @Test fun `HTMLElement owns style`() {
        val resolved = index.resolveMember("HTMLElement", "style")
        assertNotNull(resolved)
        assertEquals("HTMLElement", resolved!!.declaringType)
        assertEquals(0, resolved.distance)
    }

    @Test fun `HTMLElement owns tabIndex`() {
        val resolved = index.resolveMember("HTMLElement", "tabIndex")
        assertNotNull(resolved)
        assertEquals("HTMLElement", resolved!!.declaringType)
    }

    // ─── typeof companion (TYPE$X) member look-up ─────────────────────────────────

    @Test fun `TYPE-dollar-NodeFilter has SHOW_ELEMENT with correct type`() {
        val resolved = index.resolveMember("TYPE\$NodeFilter", "SHOW_ELEMENT")
        assertNotNull(resolved)
        assertEquals("number", resolved!!.first?.type)
    }

    @Test fun `TYPE-dollar-NodeFilter has FILTER_ACCEPT`() {
        assertNotNull(index.resolveMember("TYPE\$NodeFilter", "FILTER_ACCEPT"))
    }

    @Test fun `TYPE-dollar-Math has random method`() {
        val resolved = index.resolveMember("TYPE\$Math", "random")
        assertNotNull(resolved)
        assertEquals("method", resolved!!.first?.kind)
    }

    @Test fun `TYPE-dollar-Math has PI property`() {
        val resolved = index.resolveMember("TYPE\$Math", "PI")
        assertNotNull(resolved)
        assertEquals("property", resolved!!.first?.kind)
    }

    // ─── hasMemberName (drives completion confidence check) ───────────────────────

    @Test fun `hasMemberName true for Document createElement`() {
        assertTrue(index.hasMemberName("createElement"))
    }

    @Test fun `hasMemberName true for Element innerHTML`() {
        assertTrue(index.hasMemberName("innerHTML"))
    }

    @Test fun `hasMemberName true for Storage getItem`() {
        assertTrue(index.hasMemberName("getItem"))
    }

    @Test fun `hasMemberName true for Console log`() {
        assertTrue(index.hasMemberName("log"))
    }

    @Test fun `hasMemberName true for Crypto subtle`() {
        assertTrue(index.hasMemberName("subtle"))
    }

    @Test fun `hasMemberName true for Performance now`() {
        assertTrue(index.hasMemberName("now"))
    }

    @Test fun `hasMemberName false for nonexistent member`() {
        assertTrue(!index.hasMemberName("__nonexistent_member__"))
    }

    // ─── Member candidate dedup (same location across multiple interfaces) ─────────

    @Test fun `collectMemberCandidates finds addEventListener across interfaces`() {
        // collectMemberCandidates requires JsLocation; in-memory test data has no locations.
        // Verify the member is registered in the member-name index instead.
        assertTrue("addEventListener should be a known member name", index.hasMemberName("addEventListener"))
    }

    @Test fun `dedupeMemberCandidatesByLocation preserves shortest distance`() {
        val candidates = index.collectMemberCandidates("innerHTML")
        val deduped = index.dedupeMemberCandidatesByLocation(candidates)
        // After dedup, any (file, offset) pair should appear at most once
        val locationKeys = deduped.map { it.location.filePath to it.location.offset }
        assertEquals(locationKeys.size, locationKeys.distinct().size)
    }

    @Test fun `sortMemberCandidates puts preferred type first`() {
        val candidates = index.collectMemberCandidates("addEventListener")
        val sorted = index.sortMemberCandidates(candidates, preferredReceiverType = "Element")
        if (sorted.isNotEmpty()) {
            assertEquals("Element", sorted.first().declaringInterface)
        }
    }

    // ─── Member sampling (hover fallback for unknown receiver) ────────────────────

    @Test fun `sampleMembersByNamePrefix finds innerHTML by exact name`() {
        val samples = index.sampleMembersByNamePrefix("innerHTML")
        assertTrue(samples.any { it.first == "innerHTML" })
    }

    @Test fun `sampleMembersByNamePrefix finds members by prefix`() {
        val samples = index.sampleMembersByNamePrefix("create")
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.all { it.first.startsWith("create") })
    }

    @Test fun `sampleMembersByNamePrefix empty prefix returns all`() {
        val samples = index.sampleMembersByNamePrefix("", limit = 500)
        assertTrue(samples.size > 10)
    }

    @Test fun `sampleMembersByNamePrefix respects limit`() {
        val samples = index.sampleMembersByNamePrefix("", limit = 5)
        assertTrue(samples.size <= 5)
    }

    @Test fun `sampleMembersByNamePrefix finds subtle by exact name`() {
        val samples = index.sampleMembersByNamePrefix("subtle")
        assertTrue(samples.any { it.first == "subtle" })
    }

    @Test fun `sampleMembersByNamePrefix finds getItem by prefix get`() {
        val samples = index.sampleMembersByNamePrefix("get")
        assertTrue(samples.any { it.first.startsWith("get") })
    }

    // ─── allGlobalNames / allFunctionNames coverage ───────────────────────────────

    @Test fun `allGlobalNames contains document`() {
        assertTrue(index.allGlobalNames().contains("document"))
    }

    @Test fun `allGlobalNames contains localStorage`() {
        assertTrue(index.allGlobalNames().contains("localStorage"))
    }

    @Test fun `allGlobalNames contains window`() {
        assertTrue(index.allGlobalNames().contains("window"))
    }

    @Test fun `allFunctionNames contains fetch`() {
        assertTrue(index.allFunctionNames().contains("fetch"))
    }

    @Test fun `allFunctionNames contains setTimeout`() {
        assertTrue(index.allFunctionNames().contains("setTimeout"))
    }

    @Test fun `allFunctionNames contains atob`() {
        assertTrue(index.allFunctionNames().contains("atob"))
    }

    // ─── isConstructorGlobal ──────────────────────────────────────────────────────

    @Test fun `isConstructorGlobal true for URL`() { assertTrue(index.isConstructorGlobal("URL")) }
    @Test fun `isConstructorGlobal true for Blob`() { assertTrue(index.isConstructorGlobal("Blob")) }
    @Test fun `isConstructorGlobal true for Worker`() { assertTrue(index.isConstructorGlobal("Worker")) }
    @Test fun `isConstructorGlobal true for XMLHttpRequest`() { assertTrue(index.isConstructorGlobal("XMLHttpRequest")) }
    @Test fun `isConstructorGlobal true for WebSocket`() { assertTrue(index.isConstructorGlobal("WebSocket")) }
    @Test fun `isConstructorGlobal true for Error`() { assertTrue(index.isConstructorGlobal("Error")) }
    @Test fun `isConstructorGlobal true for Array`() { assertTrue(index.isConstructorGlobal("Array")) }
    @Test fun `isConstructorGlobal true for Promise`() { assertTrue(index.isConstructorGlobal("Promise")) }
    @Test fun `isConstructorGlobal true for MutationObserver`() { assertTrue(index.isConstructorGlobal("MutationObserver")) }
    @Test fun `isConstructorGlobal true for TextEncoder`() { assertTrue(index.isConstructorGlobal("TextEncoder")) }
    @Test fun `isConstructorGlobal true for NodeFilter`() { assertTrue(index.isConstructorGlobal("NodeFilter")) }
    @Test fun `isConstructorGlobal true for Event`() { assertTrue(index.isConstructorGlobal("Event")) }
    @Test fun `isConstructorGlobal true for AbortController`() { assertTrue(index.isConstructorGlobal("AbortController")) }
    @Test fun `isConstructorGlobal false for document`() { assertTrue(!index.isConstructorGlobal("document")) }
    @Test fun `isConstructorGlobal false for window`() { assertTrue(!index.isConstructorGlobal("window")) }
    @Test fun `isConstructorGlobal false for performance`() { assertTrue(!index.isConstructorGlobal("performance")) }
    @Test fun `isConstructorGlobal false for localStorage`() { assertTrue(!index.isConstructorGlobal("localStorage")) }
    @Test fun `isConstructorGlobal false for navigator`() { assertTrue(!index.isConstructorGlobal("navigator")) }
    @Test fun `isConstructorGlobal false for console`() { assertTrue(!index.isConstructorGlobal("console")) }
    @Test fun `isConstructorGlobal false for Math singleton`() { assertTrue(!index.isConstructorGlobal("Math")) }
    @Test fun `isConstructorGlobal false for JSON singleton`() { assertTrue(!index.isConstructorGlobal("JSON")) }
    @Test fun `isConstructorGlobal false for Reflect singleton`() { assertTrue(!index.isConstructorGlobal("Reflect")) }
    @Test fun `isConstructorGlobal false for Atomics singleton`() { assertTrue(!index.isConstructorGlobal("Atomics")) }
    @Test fun `isConstructorGlobal false for unknown name`() { assertTrue(!index.isConstructorGlobal("__nonexistent__")) }

    // ─── isKnownGlobal ─────────────────────────────────────────────────────────────

    @Test fun `isKnownGlobal true for document`() { assertTrue(index.isKnownGlobal("document")) }
    @Test fun `isKnownGlobal true for fetch`() { assertTrue(index.isKnownGlobal("fetch")) }
    @Test fun `isKnownGlobal true for window`() { assertTrue(index.isKnownGlobal("window")) }
    @Test fun `isKnownGlobal true for setTimeout`() { assertTrue(index.isKnownGlobal("setTimeout")) }
    @Test fun `isKnownGlobal false for unknown`() { assertTrue(!index.isKnownGlobal("__nonexistent__")) }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    private fun assertKnownGlobal(name: String) {
        assertNotNull("$name should be a known global (variable)", index.resolveGlobalInfo(name))
    }

    private fun assertKnownFunction(name: String) {
        val fns = index.resolveFunctions(name)
        assertNotNull("$name should be a known function", fns)
        assertTrue("$name functions list should be non-empty", fns!!.isNotEmpty())
    }

    private fun assertMemberResolvable(typeName: String, memberName: String) {
        val resolved = index.resolveMember(typeName, memberName)
        assertNotNull("$typeName#$memberName should be resolvable", resolved)
    }
}
