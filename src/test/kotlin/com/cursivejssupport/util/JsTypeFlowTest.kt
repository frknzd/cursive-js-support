package com.cursivejssupport.util

import com.cursivejssupport.index.JsTypeRef
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsParam
import com.cursivejssupport.types.JsCallSignature
import org.junit.Assert.assertEquals
import org.junit.Test

class JsTypeFlowTest {
    @Test
    fun `collection element survives nullable generic array return`() {
        val type = JsTypeRef.parse("ChangeObject<T[]>[] | undefined")

        assertEquals("ChangeObject<T[]>", collectionElementType(type)?.display())
    }

    @Test
    fun `collection element unions all array branches and ignores nullish branches`() {
        val type = JsTypeRef.parse("Change[] | Array<OtherChange> | null")

        assertEquals("Change | OtherChange", collectionElementType(type)?.display())
    }

    @Test
    fun `generic call return is substituted from the actual argument`() {
        val inferred = JsTypeFlow.inferCall(
            listOf(JsCallSignature(listOf(JsParam("value", "T")), JsTypeRef.parse("Promise<T>"))),
            listOf(JsTypeRef.Named("Response")),
        )

        assertEquals("Promise<Response>", inferred?.type?.display())
    }

    @Test
    fun `generic callback parameter is instantiated from sibling arguments`() {
        val signature = JsCallSignature(
            listOf(JsParam("items", "Array<T>"), JsParam("callback", "Callback<T>")),
            JsTypeRef.Named("void"),
        )

        val params = JsTypeFlow.instantiateParams(signature, listOf(JsTypeRef.parse("ChangeObject[]"), null))

        assertEquals("Callback<ChangeObject>", params[1].type)
    }

    @Test
    fun `argument types select the matching overload`() {
        val inferred = JsTypeFlow.inferIndexedCall(
            listOf(
                JsMember(params = listOf(JsParam("value", "string")), returns = "TextResult"),
                JsMember(params = listOf(JsParam("value", "number")), returns = "NumberResult"),
            ),
            listOf(JsTypeRef.Named("number")),
        )

        assertEquals("NumberResult", inferred?.type?.display())
    }

    @Test
    fun `tuple keyed access and map iteration retain component types`() {
        val tuple = collectionElementType(JsTypeRef.parse("Map<string, ChangeObject>"))!!

        assertEquals("[string, ChangeObject]", tuple.display())
        assertEquals("ChangeObject", JsTypeFlow.keyedAccess(tuple, "1") { _, _ -> null }?.display())
    }

    @Test
    fun `promise flow awaits nested promises and preserves union results`() {
        assertEquals("ChangeObject", JsTypeFlow.awaited(JsTypeRef.parse("Promise<Promise<ChangeObject>>")).display())
        assertEquals("ChangeObject", JsTypeFlow.removeNullish(JsTypeRef.parse("ChangeObject | null | undefined")).display())
    }

    @Test
    fun `inline callback parameters are recovered from arrow type text`() {
        val params = JsTypeFlow.callbackParameters("(change: ChangeObject, index: number) => boolean")

        assertEquals(listOf("ChangeObject", "number"), params.map { it.display() })
    }

    @Test
    fun `js object and array literals retain structural member and element types`() {
        val objectLiteral = JsTypeFlow.jsLiteral("#js {:added true :count 2 :label \"ok\"}")!!
        val arrayLiteral = JsTypeFlow.jsLiteral("#js [1 2 3]")!!

        assertEquals(
            mapOf("added" to "boolean", "count" to "number", "label" to "string"),
            objectLiteral.returnMembers.associate { it.name to it.type },
        )
        assertEquals("number[]", arrayLiteral.type.display())
    }

    @Test
    fun `anonymous npm return records expose members after array element flow`() {
        val element = collectionElementType(JsTypeRef.parse("{ added: boolean; count?: number }[]"))!!
        val resolution = TypeResolution(element, confident = true)

        assertEquals(
            mapOf("added" to "boolean", "count" to "number | undefined"),
            resolution.effectiveSemanticMembers.associate { it.name to it.type },
        )
    }
}
