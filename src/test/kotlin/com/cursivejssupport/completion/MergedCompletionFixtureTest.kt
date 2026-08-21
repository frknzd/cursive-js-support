package com.cursivejssupport.completion

import com.cursivejssupport.index.JsIndexLoader
import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.project.CljsProjectModel
import com.cursivejssupport.settings.JsSupportSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ComponentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cursive.file.ClojureScriptFileType

/** Exercises the final lookup list after this plugin and another contributor both participate. */
class MergedCompletionFixtureTest : BasePlatformTestCase() {
    private val contributor = InteropCompletionContributor()
    private val cursiveContributor by lazy {
        Class.forName("cursive.completion.ClojureCompletionContributor")
            .getDeclaredConstructor()
            .newInstance() as CompletionContributor
    }
    private var registeredLanguage: Language? = null
    private var autocompleteOnCompletion = true

    override fun setUp() {
        super.setUp()
        autocompleteOnCompletion = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        registerService(ApplicationManager.getApplication(), JsSupportSettings::class.java, JsSupportSettings())
        val index = JsSymbolIndex()
        JsIndexLoader.loadBundledBrowser(index)
        index.setLoaded(true)
        registerService(project, JsSymbolIndex::class.java, index)
        registerService(project, CljsProjectModel::class.java, CljsProjectModel(project))
        seedClojureScriptSources()
    }

    override fun tearDown() {
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = autocompleteOnCompletion
        registeredLanguage?.let {
            contributorExtension().removeExplicitExtension(it, contributor)
            contributorExtension().removeExplicitExtension(it, cursiveContributor)
        }
        super.tearDown()
    }

    fun testTypedGlobalCompletionIsPresentInMergedPopup() {
        val items = complete("(ns fixture.core)\n(js/doc<caret>)")
        assertContainsElements(items.lookupStrings(), "document")
    }

    fun testTypedDocumentMembersArePresentInMergedPopup() {
        val items = complete("(ns fixture.core)\n(js/document.cre<caret>)")
        assertContainsElements(items.lookupStrings(), "createElement", "createRange", "createTreeWalker")
    }

    fun testNestedTypedMembersArePresentInMergedPopup() {
        val items = complete("(ns fixture.core)\n(js/document.body.cla<caret>)")
        assertContainsElements(items.lookupStrings(), "classList", "className")
    }

    fun testStaticPromiseMemberIsPresentInMergedPopup() {
        val items = complete("(ns fixture.core)\n(js/Promise.res<caret>)")
        assertContainsElements(items.lookupStrings(), "resolve")
    }

    fun testPrefixDotFormInfersQualifiedConstructorExpression() {
        val items = complete("(ns fixture.core)\n(.getT<caret> (js/Date.))")
        assertContainsElements(items.lookupStrings(), "getTime", "getTimezoneOffset")
    }

    fun testAuthoritativeTypedPopupExcludesCursiveGlobalNoise() {
        val strings = complete("(ns fixture.core)\n(js/document.get<caret>)").lookupStrings()
        assertContainsElements(strings, "getElementById", "getElementsByClassName", "getSelection")
        val screenshotNoise = setOf("get", "g", "__iterator__", "addRange", "alignmentBaseline", "allMapValues")
        assertTrue("Cursive global/prototype noise leaked into typed Date popup: ${strings intersect screenshotNoise}",
            strings.intersect(screenshotNoise).isEmpty())
    }

    fun testDotDotPropertyFlowUsesPriorStepReturnType() {
        val items = complete("(ns fixture.core)\n(.. js/document -body -classN<caret>)")
        assertContainsElements(items.lookupStrings(), "-className")
    }

    fun testThreadFirstPropertyFlowUsesPriorStepReturnType() {
        val items = complete("(ns fixture.core)\n(-> js/document .-body .-classN<caret>)")
        assertContainsElements(items.lookupStrings(), ".-className")
    }

    fun testDotoKeepsRootAsReceiver() {
        val items = complete("(ns fixture.core)\n(doto js/document (.cre<caret>))")
        assertContainsElements(items.lookupStrings(), ".createElement", ".createRange")
    }

    fun testUnknownReceiverMethodFallbackRemainsMethodOnly() {
        val items = complete("(ns fixture.core)\n(defn f [event] (.sto<caret> event))")
        val strings = items.lookupStrings()
        assertContainsElements(strings, "stop", "stopImmediatePropagation", "stopPropagation")
        assertFalse("property leaked into dot-method completion: $strings", "stopColor" in strings)
        assertFalse("property leaked into dot-method completion: $strings", "storage" in strings)
    }

    fun testUnknownReceiverFallbackWaitsForMeaningfulPrefix() {
        val items = complete("(ns fixture.core)\n(defn f [event] (.s<caret> event))", requireItems = false)
        val strings = items.lookupStrings()
        assertFalse("generic fallback appeared for a one-character prefix: $strings", "stop" in strings)
        assertFalse("generic fallback appeared for a one-character prefix: $strings", "stopPropagation" in strings)
    }

    fun testInteropRowsAreNotDuplicatedInMergedPopup() {
        val items = complete("(ns fixture.core)\n(js/document.body.cla<caret>)")
        val duplicates = items.groupingBy(LookupElement::getLookupString).eachCount().filterValues { it > 1 }
        assertTrue("duplicate merged completion rows: $duplicates", duplicates.isEmpty())
    }

    fun testCursiveContributorExecutesInHeadlessFixture() {
        val scenarios = listOf(
            "local definition" to "(ns fixture.core)\n(def local-value 1)\n(loc<caret>)",
            "lexical binding" to "(ns fixture.core)\n(defn f [local-value] (loc<caret>))",
            "core function" to "(ns fixture.core)\n(ma<caret>)",
            "special form" to "(ns fixture.core)\n(de<caret>)",
            "cljs.core qualified" to "(ns fixture.core)\n(cljs.core/ma<caret>)",
            "project namespace" to "(ns fixture.core (:require [fixture.ut<caret>]))",
        )
        val results = scenarios.associate { (name, source) -> name to complete(source, requireItems = false).lookupStrings() }
        assertEquals("cursive.completion.ClojureCompletionContributor", cursiveContributor.javaClass.name)
        listOf("local definition", "lexical binding").forEach { scenario ->
            val rows = results.getValue(scenario)
            assertTrue("unexpected Cursive local rows for $scenario: $rows", rows.isEmpty() || "local" in rows)
        }
        listOf("core function", "special form", "cljs.core qualified", "project namespace").forEach { scenario ->
            assertTrue("unexpected indexed rows for $scenario: $results", results.getValue(scenario).isEmpty())
        }
    }

    fun testCursiveAndInteropRowsCoexistInSamePopup() {
        val items = complete("(ns fixture.core)\n(js/doc<caret>)")
        assertContainsElements(items.lookupStrings(), "document")
    }

    private fun complete(source: String, requireItems: Boolean = true): List<LookupElement> {
        require("<caret>" in source) { "scenario is missing <caret>" }
        myFixture.configureByText(ClojureScriptFileType.INSTANCE, source)
        val language = myFixture.file.language
        if (registeredLanguage == null) {
            contributorExtension().addExplicitExtension(language, cursiveContributor)
            contributorExtension().addExplicitExtension(language, contributor)
            registeredLanguage = language
        }
        val items = myFixture.complete(CompletionType.BASIC, 1)?.toList().orEmpty()
        if (requireItems) assertTrue("completion returned no lookup items for $source", items.isNotEmpty())
        return items
    }

    private fun List<LookupElement>.lookupStrings(): List<String> = map(LookupElement::getLookupString)

    private fun seedClojureScriptSources() {
        myFixture.addFileToProject(
            "src/cljs/core.cljs",
            "(ns cljs.core)\n(defn map [f coll])\n(defn map-indexed [f coll])\n(defn reduce [f coll])",
        )
        myFixture.addFileToProject(
            "src/fixture/util.cljs",
            "(ns fixture.util)\n(defn project-helper [value] value)\n(def project-value 1)",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun contributorExtension(): LanguageExtension<CompletionContributor> {
        val field = CompletionContributor::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        return field.get(null) as LanguageExtension<CompletionContributor>
    }

    private fun <T : Any> registerService(manager: ComponentManager, type: Class<T>, instance: T) {
        val utility = Class.forName("com.intellij.testFramework.ServiceContainerUtil")
        utility.getMethod(
            "registerOrReplaceServiceInstance",
            ComponentManager::class.java,
            Class::class.java,
            Any::class.java,
            Disposable::class.java,
        ).invoke(null, manager, type, instance, testRootDisposable)
    }
}
