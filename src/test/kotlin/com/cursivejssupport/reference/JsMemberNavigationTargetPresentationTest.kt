package com.cursivejssupport.reference

import org.junit.Assert.assertEquals
import org.junit.Test

class JsMemberNavigationTargetPresentationTest {

    @Test fun `numbered alternative row`() {
        assertEquals(
            "scrollTop in Element (2/11)  —  scrollTop: number",
            JsMemberNavigationTarget.presentableRowText(
                name = "scrollTop",
                declaringInterface = "Element",
                ordinal = 2,
                total = 11,
                signature = "scrollTop: number",
                deprecated = false,
            ),
        )
    }

    @Test fun `numbered row without signature metadata skips the signature segment`() {
        assertEquals(
            "focus in HTMLElement (1/3)",
            JsMemberNavigationTarget.presentableRowText(
                name = "focus",
                declaringInterface = "HTMLElement",
                ordinal = 1,
                total = 3,
                signature = "focus",
                deprecated = false,
            ),
        )
    }

    @Test fun `unnumbered row keeps signature-first layout`() {
        assertEquals(
            "createRange(): Range  —  Document",
            JsMemberNavigationTarget.presentableRowText(
                name = "createRange",
                declaringInterface = "Document",
                ordinal = null,
                total = null,
                signature = "createRange(): Range",
                deprecated = false,
            ),
        )
    }

    @Test fun `deprecated suffix appears on both layouts`() {
        assertEquals(
            "charset in Document (3/4)  —  charset: string  —  @deprecated",
            JsMemberNavigationTarget.presentableRowText(
                name = "charset",
                declaringInterface = "Document",
                ordinal = 3,
                total = 4,
                signature = "charset: string",
                deprecated = true,
            ),
        )
        assertEquals(
            "charset: string  —  Document  —  @deprecated",
            JsMemberNavigationTarget.presentableRowText(
                name = "charset",
                declaringInterface = "Document",
                ordinal = null,
                total = null,
                signature = "charset: string",
                deprecated = true,
            ),
        )
    }
}
