package com.cursivejssupport.completion

import com.cursivejssupport.npm.NpmBinding
import com.cursivejssupport.npm.NpmBindingKind
import com.cursivejssupport.util.ChainKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteropContextDetectorTest {

    private val GOOG_NS = setOf("goog", "goog.array", "goog.dom", "goog.string", "goog.structs", "goog.events")

    private fun detect(
        text: String,
        aliases: Map<String, NpmBinding> = emptyMap(),
        googNs: Set<String> = emptySet(),
    ): InteropCompletionContext =
        InteropContextDetector.detect(text, text.length, aliases, googNs)

    private fun asBinding(pkg: String) = NpmBinding(pkg, NpmBindingKind.AS)
    private fun defaultBinding(pkg: String) = NpmBinding(pkg, NpmBindingKind.DEFAULT)
    private fun allBinding(pkg: String) = NpmBinding(pkg, NpmBindingKind.ALL)
    private fun referBinding(pkg: String, export: String) = NpmBinding(pkg, NpmBindingKind.REFER, export)

    @Test
    fun `js slash empty prefix yields js global with empty prefix`() {
        val ctx = detect("(js/")
        assertTrue(ctx is InteropCompletionContext.JsGlobalName)
        ctx as InteropCompletionContext.JsGlobalName
        assertEquals("", ctx.prefix)
        assertEquals("(js/".length, ctx.replacementStart)
    }

    @Test
    fun `js slash partial global`() {
        val ctx = detect("(js/doc")
        assertTrue(ctx is InteropCompletionContext.JsGlobalName)
        ctx as InteropCompletionContext.JsGlobalName
        assertEquals("doc", ctx.prefix)
        assertEquals("(js/".length, ctx.replacementStart)
    }

    @Test
    fun `js chain with trailing dot yields chain member empty prefix`() {
        val ctx = detect("(js/document.")
        assertTrue(ctx is InteropCompletionContext.JsChainMember)
        ctx as InteropCompletionContext.JsChainMember
        assertEquals(listOf("document"), ctx.receiverSegments)
        assertEquals("", ctx.prefix)
        assertEquals("(js/document.".length, ctx.replacementStart)
    }

    @Test
    fun `js chain with partial member`() {
        val ctx = detect("(js/document.cre")
        assertTrue(ctx is InteropCompletionContext.JsChainMember)
        ctx as InteropCompletionContext.JsChainMember
        assertEquals(listOf("document"), ctx.receiverSegments)
        assertEquals("cre", ctx.prefix)
        assertEquals("(js/document.".length, ctx.replacementStart)
    }

    @Test
    fun `js multi segment chain`() {
        val ctx = detect("(js/console.log.app")
        assertTrue(ctx is InteropCompletionContext.JsChainMember)
        ctx as InteropCompletionContext.JsChainMember
        assertEquals(listOf("console", "log"), ctx.receiverSegments)
        assertEquals("app", ctx.prefix)
    }

    @Test
    fun `dot method token at head`() {
        val ctx = detect("(.lo")
        assertTrue(ctx is InteropCompletionContext.DotMember)
        ctx as InteropCompletionContext.DotMember
        assertEquals(false, ctx.asProperty)
        assertEquals("lo", ctx.prefix)
        assertEquals("(.".length, ctx.replacementStart)
    }

    @Test
    fun `dot property token at head`() {
        val ctx = detect("(.-cla")
        assertTrue(ctx is InteropCompletionContext.DotMember)
        ctx as InteropCompletionContext.DotMember
        assertEquals(true, ctx.asProperty)
        assertEquals("cla", ctx.prefix)
        assertEquals("(.-".length, ctx.replacementStart)
    }

    @Test
    fun `bare dot yields dot method with empty prefix`() {
        val ctx = detect("(.")
        assertTrue(ctx is InteropCompletionContext.DotMember)
        ctx as InteropCompletionContext.DotMember
        assertEquals(false, ctx.asProperty)
        assertEquals("", ctx.prefix)
    }

    @Test
    fun `bare dot dash yields dot property with empty prefix`() {
        val ctx = detect("(.-")
        assertTrue(ctx is InteropCompletionContext.DotMember)
        ctx as InteropCompletionContext.DotMember
        assertEquals(true, ctx.asProperty)
        assertEquals("", ctx.prefix)
    }

    @Test
    fun `npm alias export prefix`() {
        val ctx = detect("(Markdown/defa", aliases = mapOf("Markdown" to asBinding("react-markdown")))
        assertTrue(ctx is InteropCompletionContext.NpmAliasExport)
        ctx as InteropCompletionContext.NpmAliasExport
        assertEquals("Markdown", ctx.alias)
        assertEquals("react-markdown", ctx.packageName)
        assertEquals("defa", ctx.prefix)
        assertEquals("(Markdown/".length, ctx.replacementStart)
    }

    @Test
    fun `npm alias export member chain`() {
        val ctx = detect("(Markdown/default.ren", aliases = mapOf("Markdown" to asBinding("react-markdown")))
        assertTrue(ctx is InteropCompletionContext.NpmAliasExportMember)
        ctx as InteropCompletionContext.NpmAliasExportMember
        assertEquals("Markdown", ctx.alias)
        assertEquals("react-markdown", ctx.packageName)
        assertEquals("default", ctx.exportName)
        assertEquals(emptyList<String>(), ctx.receiverSegments)
        assertEquals("ren", ctx.prefix)
    }

    @Test
    fun `npm alias export member chain deeper receiver`() {
        val ctx = detect("(Markdown/default.props.cla", aliases = mapOf("Markdown" to asBinding("react-markdown")))
        assertTrue(ctx is InteropCompletionContext.NpmAliasExportMember)
        ctx as InteropCompletionContext.NpmAliasExportMember
        assertEquals("default", ctx.exportName)
        assertEquals(listOf("props"), ctx.receiverSegments)
        assertEquals("cla", ctx.prefix)
    }

    @Test
    fun `default binding slash token yields None`() {
        val ctx = detect("(MyDefault/foo", aliases = mapOf("MyDefault" to defaultBinding("my-pkg")))
        assertEquals(InteropCompletionContext.None, ctx)
    }

    @Test
    fun `refer binding slash token yields None`() {
        val ctx = detect("(useState/something", aliases = mapOf("useState" to referBinding("react", "useState")))
        assertEquals(InteropCompletionContext.None, ctx)
    }

    @Test
    fun `all binding slash token yields NpmAliasExport`() {
        val ctx = detect("(MyAll/foo", aliases = mapOf("MyAll" to allBinding("my-pkg")))
        assertTrue(ctx is InteropCompletionContext.NpmAliasExport)
        ctx as InteropCompletionContext.NpmAliasExport
        assertEquals("MyAll", ctx.alias)
        assertEquals("my-pkg", ctx.packageName)
        assertEquals("foo", ctx.prefix)
    }

    @Test
    fun `unknown namespace is parsed as JsChainMember`() {
        val ctx = detect("(my.ns/foo")
        assertTrue(ctx is InteropCompletionContext.JsChainMember)
        ctx as InteropCompletionContext.JsChainMember
        assertEquals(listOf("my.ns"), ctx.receiverSegments)
        assertEquals("foo", ctx.prefix)
    }

    @Test
    fun `bare identifier is none`() {
        val ctx = detect("(foo bar")
        assertEquals(InteropCompletionContext.None, ctx)
    }

    @Test
    fun `require package partial string`() {
        val ctx = detect("(ns my.app (:require [\"rea")
        assertTrue(ctx is InteropCompletionContext.NsRequirePackage)
        ctx as InteropCompletionContext.NsRequirePackage
        assertEquals("rea", ctx.prefix)
    }

    @Test
    fun `require empty package string`() {
        val ctx = detect("(ns my.app (:require [\"")
        assertTrue(ctx is InteropCompletionContext.NsRequirePackage)
        ctx as InteropCompletionContext.NsRequirePackage
        assertEquals("", ctx.prefix)
    }

    @Test
    fun `string outside require is not an interop context`() {
        val ctx = detect("(def x \"hello")
        assertEquals(InteropCompletionContext.None, ctx)
    }

    @Test
    fun `refer collection partial symbol`() {
        val ctx = detect("(ns my.app (:require [\"react\" :refer [u")
        assertTrue(ctx is InteropCompletionContext.NsRefer)
        ctx as InteropCompletionContext.NsRefer
        assertEquals("react", ctx.packageName)
        assertEquals("u", ctx.prefix)
    }

    @Test
    fun `refer collection empty bracket`() {
        val ctx = detect("(ns my.app (:require [\"react\" :refer [")
        assertTrue(ctx is InteropCompletionContext.NsRefer)
        ctx as InteropCompletionContext.NsRefer
        assertEquals("react", ctx.packageName)
        assertEquals("", ctx.prefix)
    }

    @Test
    fun `rename map partial key`() {
        val ctx = detect("(ns my.app (:require [\"react\" :rename {use")
        assertTrue(ctx is InteropCompletionContext.NsRefer)
        ctx as InteropCompletionContext.NsRefer
        assertEquals("react", ctx.packageName)
        assertEquals("use", ctx.prefix)
    }

    @Test
    fun `require spec keyword slot after package`() {
        val ctx = detect("(ns my.app (:require [\"react\" ")
        assertTrue(ctx is InteropCompletionContext.NsRequireKeyword)
        ctx as InteropCompletionContext.NsRequireKeyword
        assertEquals("react", ctx.packageName)
        assertTrue(":as" in ctx.availableKeywords)
        assertTrue(":refer" in ctx.availableKeywords)
        assertTrue(":rename" in ctx.availableKeywords)
        assertTrue(":default" in ctx.availableKeywords)
        assertTrue(":all" in ctx.availableKeywords)
    }

    @Test
    fun `require spec keyword excludes already used keywords`() {
        val ctx = detect("(ns my.app (:require [\"react\" :as React ")
        assertTrue(ctx is InteropCompletionContext.NsRequireKeyword)
        ctx as InteropCompletionContext.NsRequireKeyword
        assertTrue(":as" !in ctx.availableKeywords)
        assertTrue(":refer" in ctx.availableKeywords)
    }

    @Test
    fun `bare js token without slash is none`() {
        val ctx = detect("(js")
        assertEquals(InteropCompletionContext.None, ctx)
    }

    @Test
    fun `chain after caret matches independent of trailing whitespace`() {
        // user types '(js/document.' then space → token ends at the dot, caret is past space
        val text = "(js/document. "
        val ctx = InteropContextDetector.detect(text, text.length, emptyMap<String, NpmBinding>())
        // whitespace ends the token; nothing on the right side after the dot — this is bare text
        assertEquals(InteropCompletionContext.None, ctx)
    }

    // ─── GoogNamespaceName ────────────────────────────────────────────────────

    @Test fun `bare goog with known namespaces yields GoogNamespaceName`() {
        val ctx = detect("(goog", googNs = GOOG_NS) as InteropCompletionContext.GoogNamespaceName
        assertEquals("goog", ctx.prefix)
    }

    @Test fun `goog dot partial yields GoogNamespaceName`() {
        val ctx = detect("(goog.", googNs = GOOG_NS) as InteropCompletionContext.GoogNamespaceName
        assertEquals("goog.", ctx.prefix)
    }

    @Test fun `goog dot string prefix yields GoogNamespaceName`() {
        val ctx = detect("(goog.str", googNs = GOOG_NS) as InteropCompletionContext.GoogNamespaceName
        assertEquals("goog.str", ctx.prefix)
    }

    @Test fun `goog dot dom prefix yields GoogNamespaceName`() {
        val ctx = detect("(goog.dom", googNs = GOOG_NS) as InteropCompletionContext.GoogNamespaceName
        assertEquals("goog.dom", ctx.prefix)
    }

    @Test fun `goog dot full namespace yields GoogNamespaceName`() {
        val ctx = detect("(goog.string", googNs = GOOG_NS) as InteropCompletionContext.GoogNamespaceName
        assertEquals("goog.string", ctx.prefix)
    }

    @Test fun `goog without known namespaces yields None`() {
        // No goog symbols loaded — don't offer fake completions
        assertEquals(InteropCompletionContext.None, detect("(goog", googNs = emptySet()))
    }

    @Test fun `goog slash function yields NpmAliasExport not GoogNamespaceName`() {
        // goog.string/format — slash present, the namespace-export path wins
        val ctx = detect("(goog.string/", googNs = GOOG_NS)
        assertTrue("expected NpmAliasExport, got $ctx", ctx is InteropCompletionContext.NpmAliasExport)
        ctx as InteropCompletionContext.NpmAliasExport
        assertEquals("goog.string", ctx.packageName)
    }

    @Test fun `goog slash partial function yields NpmAliasExport`() {
        val ctx = detect("(goog.string/for", googNs = GOOG_NS)
        assertTrue(ctx is InteropCompletionContext.NpmAliasExport)
        ctx as InteropCompletionContext.NpmAliasExport
        assertEquals("for", ctx.prefix)
        assertEquals("goog.string", ctx.packageName)
    }

    @Test fun `goog events namespace yields GoogNamespaceName`() {
        val ctx = detect("goog.events", googNs = GOOG_NS) as InteropCompletionContext.GoogNamespaceName
        assertEquals("goog.events", ctx.prefix)
    }

    @Test fun `shouldOpen fires for bare goog token`() {
        assertTrue(InteropAutoPopupHandler.shouldOpen("(goog", 5, knownGoogNamespaces = GOOG_NS))
    }

    @Test fun `shouldOpen fires for goog dot`() {
        assertTrue(InteropAutoPopupHandler.shouldOpen("(goog.", 6, knownGoogNamespaces = GOOG_NS))
    }

    @Test fun `shouldOpen does not fire for bare goog without index`() {
        assertFalse(InteropAutoPopupHandler.shouldOpen("(goog", 5, knownGoogNamespaces = emptySet()))
    }

    // ─── ChainStepForm ───────────────────────────────────────────────────────

    private fun chainStep(text: String): InteropCompletionContext.ChainStepForm {
        val ctx = detect(text)
        assertTrue("expected ChainStepForm for <$text> but was $ctx", ctx is InteropCompletionContext.ChainStepForm)
        return ctx as InteropCompletionContext.ChainStepForm
    }

    @Test fun `dot-dot form empty prefix at step-1 position`() {
        val ctx = chainStep("(.. js/document ")
        assertEquals(ChainKind.DOT_DOT, ctx.kind)
        assertEquals(listOf("js/document"), ctx.priorChain)
        assertEquals("", ctx.prefix)
    }

    @Test fun `dot-dot form partial method prefix`() {
        val ctx = chainStep("(.. js/document cre")
        assertEquals(listOf("js/document"), ctx.priorChain)
        assertEquals("cre", ctx.prefix)
        assertEquals("(.. js/document ".length, ctx.replacementStart)
    }

    @Test fun `dot-dot form two prior steps empty prefix`() {
        val ctx = chainStep("(.. js/document createRange ")
        assertEquals(listOf("js/document", "createRange"), ctx.priorChain)
        assertEquals("", ctx.prefix)
    }

    @Test fun `dot-dot form property prefix with dash`() {
        val ctx = chainStep("(.. js/document -inn")
        assertEquals(listOf("js/document"), ctx.priorChain)
        assertEquals("-inn", ctx.prefix)
    }

    @Test fun `dot-dot form npm alias root`() {
        val ctx = chainStep("(.. R/Component ")
        assertEquals(listOf("R/Component"), ctx.priorChain)
        assertEquals("", ctx.prefix)
    }

    @Test fun `dot-dot form three steps partial`() {
        val ctx = chainStep("(.. js/document body firstChild nodeV")
        assertEquals(listOf("js/document", "body", "firstChild"), ctx.priorChain)
        assertEquals("nodeV", ctx.prefix)
    }

    @Test fun `dot-dot form at root position returns non-ChainStepForm`() {
        // caret is right after `..` — position 0, the root.  Existing logic handles it.
        val ctx = detect("(.. ")
        assertFalse(ctx is InteropCompletionContext.ChainStepForm)
    }

    @Test fun `chain form does not fire outside chain forms`() {
        // `do` form: not a chain macro
        val ctx = detect("(do js/document ")
        assertFalse(ctx is InteropCompletionContext.ChainStepForm)
    }

    @Test fun `dot-dot form nested inside outer list`() {
        val ctx = chainStep("(let [x 1] (.. js/document ")
        assertEquals(listOf("js/document"), ctx.priorChain)
        assertEquals("", ctx.prefix)
    }

    @Test fun `dot-dot form list-step head position`() {
        val ctx = chainStep("(.. js/document (crea")
        assertEquals(ChainKind.DOT_DOT, ctx.kind)
        assertEquals(listOf("js/document"), ctx.priorChain)
        assertEquals("crea", ctx.prefix)
    }

    @Test fun `dot-dot form prior list step is kept whole`() {
        val ctx = chainStep("(.. js/document (createElement \"div\") -sty")
        assertEquals(listOf("js/document", "(createElement \"div\")"), ctx.priorChain)
        assertEquals("-sty", ctx.prefix)
    }

    @Test fun `thread-first bare dot step`() {
        val ctx = chainStep("(-> js/document .cre")
        assertEquals(ChainKind.THREAD_FIRST, ctx.kind)
        assertEquals(listOf("js/document"), ctx.priorChain)
        assertEquals(".cre", ctx.prefix)
    }

    @Test fun `thread-first list-step head`() {
        val ctx = chainStep("(-> js/document (.cre")
        assertEquals(ChainKind.THREAD_FIRST, ctx.kind)
        assertEquals(listOf("js/document"), ctx.priorChain)
        assertEquals(".cre", ctx.prefix)
    }

    @Test fun `thread-first after prior steps`() {
        val ctx = chainStep("(-> js/document .-body (.appendChild el) .-parent")
        assertEquals(listOf("js/document", ".-body", "(.appendChild el)"), ctx.priorChain)
        assertEquals(".-parent", ctx.prefix)
    }

    @Test fun `thread-first bare non-dot token falls through`() {
        // Likely a plain function call — the generic classifiers should handle it.
        val ctx = detect("(-> js/document upp")
        assertFalse(ctx is InteropCompletionContext.ChainStepForm)
    }

    @Test fun `doto list-step head`() {
        val ctx = chainStep("(doto js/document (.setAtt")
        assertEquals(ChainKind.DOTO, ctx.kind)
        assertEquals(listOf("js/document"), ctx.priorChain)
        assertEquals(".setAtt", ctx.prefix)
    }

    @Test fun `thread-last bare dot step`() {
        val ctx = chainStep("(->> js/document .cre")
        assertEquals(ChainKind.THREAD_LAST, ctx.kind)
        assertEquals(listOf("js/document"), ctx.priorChain)
        assertEquals(".cre", ctx.prefix)
    }

    @Test fun `some-thread-first is recognised`() {
        val ctx = chainStep("(some-> js/document .cre")
        assertEquals(ChainKind.SOME_FIRST, ctx.kind)
    }

    @Test fun `cond-thread test position falls through`() {
        // `(cond-> x <caret>` — the caret is at a TEST position, not a step.
        val ctx = detect("(cond-> js/document .cre")
        assertFalse(ctx is InteropCompletionContext.ChainStepForm)
    }

    @Test fun `cond-thread step position is recognised`() {
        val ctx = chainStep("(cond-> js/document some-test? .cre")
        assertEquals(ChainKind.COND_FIRST, ctx.kind)
        assertEquals(listOf("js/document", "some-test?"), ctx.priorChain)
        assertEquals(".cre", ctx.prefix)
    }
}
