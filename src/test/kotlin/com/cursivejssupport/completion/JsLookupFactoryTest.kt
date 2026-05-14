package com.cursivejssupport.completion

import com.cursivejssupport.parser.JsMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsLookupFactoryTest {

    @Test
    fun jsMemberKeepsMemberLookupStringAndIcon() {
        val lookup = JsLookupFactory.jsMember(
            memberName = "createRange",
            receiverType = "Document",
            member = JsMember(kind = "method", returns = "Range"),
            insertText = "js/document.createRange",
        )

        assertEquals("js/document.createRange", lookup.lookupString)
        assertTrue(lookup.allLookupStrings.contains("createRange"))
    }

    @Test
    fun dotPropertyUsesClojureScriptPropertyInsertAndMemberLookupString() {
        val lookup = JsLookupFactory.dotMember(
            memberName = "startContainer",
            typeText = "Node",
            member = JsMember(kind = "property", type = "Node"),
        )

        assertEquals(".-startContainer", lookup.lookupString)
        assertTrue(lookup.allLookupStrings.contains("startContainer"))
    }
}
