package com.cursivejssupport.util

import com.cursivejssupport.index.JsTypeRef
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsParam
import com.cursivejssupport.types.JsCallSignature
import com.cursivejssupport.types.JsMemberDescriptor

internal data class InferredCall(
    val type: JsTypeRef,
    val returnMembers: List<JsMemberDescriptor> = emptyList(),
)

/** Pure TypeScript-shaped flow operations shared by PSI inference rules. */
internal object JsTypeFlow {
    fun instantiateParams(signature: JsCallSignature, arguments: List<JsTypeRef?>): List<JsParam> {
        val substitutions = inferSubstitutions(signature.params, arguments)
        return signature.params.map { it.copy(type = substitute(JsTypeRef.parse(it.type), substitutions).display()) }
    }

    fun instantiateParams(signature: JsMember, arguments: List<JsTypeRef?>): List<JsParam> =
        instantiateParams(JsCallSignature(signature.params, JsTypeRef.parse(signature.returns)), arguments)

    fun jsLiteral(text: String): InferredCall? {
        val compact = text.trim()
        if (compact.startsWith("#js [") || compact.startsWith("#js[")) {
            val body = compact.substringAfter('[').substringBeforeLast(']')
            val elements = splitForms(body).mapNotNull(::primitiveLiteral)
            return InferredCall(JsTypeRef.Named("Array", listOf(union(elements))))
        }
        if (compact.startsWith("#js {") || compact.startsWith("#js{")) {
            val forms = splitForms(compact.substringAfter('{').substringBeforeLast('}'))
            val members = forms.chunked(2).mapNotNull { pair ->
                if (pair.size < 2) return@mapNotNull null
                val key = pair[0].removePrefix(":").removeSurrounding("\"")
                if (key.isBlank()) return@mapNotNull null
                JsMemberDescriptor(name = key, kind = "property", type = primitiveLiteral(pair[1])?.display() ?: "any")
            }
            return InferredCall(JsTypeRef.Named("Object"), members)
        }
        return null
    }

    fun inferCall(signatures: List<JsCallSignature>, arguments: List<JsTypeRef?>): InferredCall? {
        val candidates = signatures.mapNotNull { signature ->
            score(signature.params, arguments)?.let { score ->
                val substitutions = inferSubstitutions(signature.params, arguments)
                Triple(score, substitute(signature.returns, substitutions), signature.returnMembers)
            }
        }
        if (candidates.isEmpty()) return null
        val best = candidates.maxOf { it.first }
        val selected = candidates.filter { it.first == best }
        return InferredCall(union(selected.map { it.second }), selected.flatMap { it.third }.distinctBy { it.name })
    }

    fun inferIndexedCall(signatures: List<JsMember>, arguments: List<JsTypeRef?>): InferredCall? =
        inferCall(signatures.map { signature ->
            JsCallSignature(signature.params, JsTypeRef.parse(signature.returns))
        }, arguments)

    fun callbackParameters(type: JsTypeRef, signatures: (JsTypeRef) -> List<JsMember>): List<JsTypeRef> {
        val indexed = signatures(type).firstOrNull()?.params?.map { JsTypeRef.parse(it.type) }.orEmpty()
        if (indexed.isNotEmpty()) return indexed
        return callbackParameters(type.display())
    }

    fun callbackParameters(display: String): List<JsTypeRef> {
        val arrow = display.indexOf("=>")
        if (arrow < 0) return emptyList()
        val raw = display.substring(0, arrow).trim().removeSurrounding("(", ")")
        return splitTopLevel(raw).mapNotNull { parameter ->
            parameter.substringAfter(':', missingDelimiterValue = "").trim()
                .removePrefix("...").takeIf { it.isNotEmpty() }?.let(JsTypeRef::parse)
        }
    }

    fun keyedAccess(container: JsTypeRef, key: String, memberType: (JsTypeRef, String) -> JsTypeRef?): JsTypeRef? {
        if (container is JsTypeRef.Named && container.name == "Tuple") {
            return key.toIntOrNull()?.let(container.args::getOrNull)
        }
        if (container is JsTypeRef.Named && container.name in setOf("Map", "ReadonlyMap")) {
            return container.args.getOrNull(1)
        }
        if (container is JsTypeRef.Named && container.name == "Record") return container.args.getOrNull(1)
        return memberType(container, key)
    }

    fun removeNullish(type: JsTypeRef): JsTypeRef = when (type) {
        is JsTypeRef.Union -> union(type.members.filterNot(::isNullish))
        else -> type
    }

    fun awaited(type: JsTypeRef): JsTypeRef = when (type) {
        is JsTypeRef.Named -> if (type.name in setOf("Promise", "PromiseLike", "Thenable")) {
            type.args.firstOrNull()?.let(::awaited) ?: JsTypeRef.Unknown
        } else type
        is JsTypeRef.Union -> union(type.members.map(::awaited))
        is JsTypeRef.Record -> type
        JsTypeRef.Unknown -> type
    }

    fun union(types: List<JsTypeRef>): JsTypeRef {
        val flattened = types.flatMap { if (it is JsTypeRef.Union && !it.intersection) it.members else listOf(it) }.distinct()
        return when (flattened.size) {
            0 -> JsTypeRef.Unknown
            1 -> flattened.first()
            else -> JsTypeRef.Union(flattened)
        }
    }

    private fun score(params: List<JsParam>, args: List<JsTypeRef?>): Int? {
        val required = params.count { !it.optional && !it.rest }
        if (args.size < required || params.none { it.rest } && args.size > params.size) return null
        var score = 0
        args.forEachIndexed { index, actual ->
            if (actual == null || actual == JsTypeRef.Unknown) return@forEachIndexed
            val parameter = params.getOrNull(index) ?: params.lastOrNull { it.rest } ?: return null
            val expected = JsTypeRef.parse(parameter.type)
            score += compatibility(expected, actual) ?: return null
        }
        return score
    }

    private fun compatibility(expected: JsTypeRef, actual: JsTypeRef): Int? {
        if (expected == JsTypeRef.Unknown || isGeneric(expected)) return 1
        if (expected == actual) return 8
        if (expected is JsTypeRef.Union) return expected.members.mapNotNull { compatibility(it, actual) }.maxOrNull()
        if (actual is JsTypeRef.Union) return actual.members.mapNotNull { compatibility(expected, it) }.minOrNull()
        val expectedName = expected.primaryName()
        val actualName = actual.primaryName()
        if (expectedName == actualName) return 5
        if (expectedName in JsTypeRef.PRIMITIVE_TYPES && actualName in JsTypeRef.PRIMITIVE_TYPES) return null
        return 0
    }

    private fun inferSubstitutions(params: List<JsParam>, args: List<JsTypeRef?>): Map<String, JsTypeRef> {
        val result = linkedMapOf<String, JsTypeRef>()
        args.forEachIndexed { index, actual ->
            val parameter = params.getOrNull(index) ?: params.lastOrNull { it.rest }
            if (parameter != null && actual != null) unify(JsTypeRef.parse(parameter.type), actual, result)
        }
        return result
    }

    private fun unify(pattern: JsTypeRef, actual: JsTypeRef, out: MutableMap<String, JsTypeRef>) {
        if (isGeneric(pattern)) {
            val name = (pattern as JsTypeRef.Named).name
            out[name] = out[name]?.let { union(listOf(it, actual)) } ?: actual
            return
        }
        if (pattern is JsTypeRef.Union) {
            pattern.members.firstOrNull { compatibility(it, actual) != null }?.let { unify(it, actual, out) }
            return
        }
        if (pattern is JsTypeRef.Named && actual is JsTypeRef.Named && pattern.name == actual.name) {
            pattern.args.zip(actual.args).forEach { (p, a) -> unify(p, a, out) }
        }
        if (pattern is JsTypeRef.Record && actual is JsTypeRef.Record) {
            pattern.properties.forEach { (name, expected) -> actual.properties[name]?.let { unify(expected, it, out) } }
        }
    }

    private fun substitute(type: JsTypeRef, substitutions: Map<String, JsTypeRef>): JsTypeRef = when (type) {
        is JsTypeRef.Named -> substitutions[type.name]?.takeIf { type.args.isEmpty() }
            ?: type.copy(args = type.args.map { substitute(it, substitutions) })
        is JsTypeRef.Union -> type.copy(members = type.members.map { substitute(it, substitutions) })
        is JsTypeRef.Record -> type.copy(
            properties = type.properties.mapValues { substitute(it.value, substitutions) },
            indexValue = type.indexValue?.let { substitute(it, substitutions) },
        )
        JsTypeRef.Unknown -> type
    }

    private fun isGeneric(type: JsTypeRef): Boolean = type is JsTypeRef.Named && type.args.isEmpty() &&
        (type.name.length == 1 && type.name[0].isUpperCase() || type.name.matches(Regex("T[A-Z][A-Za-z0-9_]*")))

    private fun isNullish(type: JsTypeRef): Boolean =
        type is JsTypeRef.Named && type.name in setOf("null", "undefined", "nil")

    private fun splitTopLevel(value: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var depth = 0
        value.forEachIndexed { index, char ->
            when (char) {
                '(', '[', '{', '<' -> depth++
                ')', ']', '}', '>' -> depth--
                ',' -> if (depth == 0) {
                    out += value.substring(start, index)
                    start = index + 1
                }
            }
        }
        out += value.substring(start)
        return out.filter(String::isNotBlank)
    }

    private fun primitiveLiteral(value: String): JsTypeRef? = when {
        value == "nil" || value == "js/undefined" -> JsTypeRef.Named("null")
        value == "true" || value == "false" -> JsTypeRef.Named("boolean")
        value.matches(Regex("[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")) -> JsTypeRef.Named("number")
        value.length >= 2 && value.first() == '"' && value.last() == '"' -> JsTypeRef.Named("string")
        value.startsWith("#js[") || value.startsWith("#js [") -> JsTypeRef.Named("Array", listOf(JsTypeRef.Unknown))
        value.startsWith("#js{") || value.startsWith("#js {") -> JsTypeRef.Named("Object")
        else -> null
    }

    private fun splitForms(value: String): List<String> {
        val out = mutableListOf<String>()
        var start = -1
        var depth = 0
        var quoted = false
        var escaped = false
        value.forEachIndexed { index, char ->
            if (start < 0 && !char.isWhitespace() && char != ',') start = index
            if (start < 0) return@forEachIndexed
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',', ' ', '\n', '\r', '\t' -> if (depth == 0) {
                    out += value.substring(start, index)
                    start = -1
                }
            }
        }
        if (start >= 0) out += value.substring(start)
        return out
    }
}
