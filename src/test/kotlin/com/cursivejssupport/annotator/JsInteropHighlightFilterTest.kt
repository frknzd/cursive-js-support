package com.cursivejssupport.annotator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsInteropHighlightFilterTest {

    @Test
    fun typoSeverityNameIsClassifiedAsTypo() {
        assertTrue(JsInteropHighlightFilter.isTypoMetadata("TYPO", null, "Typo: In word 'createRange'"))
        assertTrue(JsInteropHighlightFilter.isTypoMetadata("typo", null, ""))
    }

    @Test
    fun spellCheckerInspectionIdIsClassifiedAsTypo() {
        assertTrue(JsInteropHighlightFilter.isTypoMetadata("WARNING", "SpellCheckingInspection", "Typo"))
        assertTrue(JsInteropHighlightFilter.isTypoMetadata(null, "SpellChecking", ""))
    }

    @Test
    fun typoPrefixDescriptionIsClassifiedAsTypo() {
        assertTrue(JsInteropHighlightFilter.isTypoMetadata("WARNING", null, "Typo: createRange"))
    }

    @Test
    fun nonTypoHighlightsAreNotMisclassified() {
        assertFalse(JsInteropHighlightFilter.isTypoMetadata("ERROR", "UnresolvedReference", "Cannot resolve symbol"))
        assertFalse(JsInteropHighlightFilter.isTypoMetadata(null, null, ""))
        assertFalse(JsInteropHighlightFilter.isTypoMetadata("WARNING", null, "Suspicious method call"))
    }

    @Test
    fun trailingMemberAfterJsSlashIsRecognized() {
        // "(js/document.createRange" – start points at "createRange"
        val text = "(js/document.createRange"
        val start = text.indexOf("createRange")
        assertTrue(JsInteropHighlightFilter.looksLikeJsTrailingMemberInSequence(text, start))
    }

    @Test
    fun trailingMemberDirectlyAfterJsSlashIsRecognized() {
        val text = "js/createRange"
        val start = text.indexOf("createRange")
        assertTrue(JsInteropHighlightFilter.looksLikeJsTrailingMemberInSequence(text, start))
    }

    @Test
    fun positionInsideMyjsTokenIsNotRecognized() {
        // `myjs/foo` should NOT match (no word boundary before "js").
        val text = "(myjs/foo"
        val start = text.indexOf("foo")
        assertFalse(JsInteropHighlightFilter.looksLikeJsTrailingMemberInSequence(text, start))
    }

    @Test
    fun nonJsPrefixIsNotRecognized() {
        val text = "(.startContainer range)"
        val start = text.indexOf("startContainer")
        assertFalse(JsInteropHighlightFilter.looksLikeJsTrailingMemberInSequence(text, start))
    }

    @Test
    fun startAtBeginningIsNotRecognized() {
        assertFalse(JsInteropHighlightFilter.looksLikeJsTrailingMemberInSequence("createRange", 0))
    }
}
