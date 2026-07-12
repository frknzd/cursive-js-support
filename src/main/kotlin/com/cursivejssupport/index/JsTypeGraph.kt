package com.cursivejssupport.index

import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsMember

internal class JsTypeGraph(
    private val interfaces: Map<String, JsInterface>,
    private val aliases: Map<String, String>,
    private val globalType: (String) -> String?,
    private val isFunction: (String) -> Boolean,
) {
    fun members(typeName: String): Map<String, JsResolvedMember> = linkedMapOf<String, JsResolvedMember>().also {
        collect(canonical(typeName), 0, mutableSetOf(), it)
    }

    private fun collect(type: String, distance: Int, seen: MutableSet<String>, out: MutableMap<String, JsResolvedMember>) {
        if (!seen.add(type)) return
        val definition = interfaces[type] ?: return
        definition.members.forEach { (name, overloads) ->
            out.putIfAbsent(name, JsResolvedMember(type, name, overloads, distance))
        }
        definition.extends.forEach { collect(canonical(it), distance + 1, seen, out) }
    }

    fun chain(segments: List<String>): JsTypeRef? {
        if (segments.isEmpty()) return null
        var type = globalType(segments.first())?.let { expand(JsTypeRef.parse(it)) }
            ?: JsTypeRef.Named("Function").takeIf { isFunction(segments.first()) }
            ?: return null
        segments.drop(1).forEach { name ->
            val member = membersOf(type)[name]?.first ?: return null
            type = substitute(memberType(member), substitution(type))
        }
        return type
    }

    fun canonical(raw: String): String {
        if (raw.none { it in "|&<[(" } && raw !in aliases) return raw
        return expand(JsTypeRef.parse(raw)).primaryName().ifEmpty { raw }
    }

    fun memberType(member: JsMember): JsTypeRef =
        expand(JsTypeRef.parse(if (member.kind == "method") member.returns else member.type))

    fun membersOf(type: JsTypeRef): Map<String, JsResolvedMember> {
        val preferred = type.primaryNamed() ?: return emptyMap()
        val direct = members(preferred.name)
        if (direct.isNotEmpty() || type !is JsTypeRef.Union) return direct
        return type.leafNameds().asSequence().filter { it.name != preferred.name }
            .map { members(it.name) }.firstOrNull(Map<String, JsResolvedMember>::isNotEmpty).orEmpty()
    }

    fun substitution(type: JsTypeRef): Map<String, JsTypeRef> {
        val named = type.primaryNamed() ?: return emptyMap()
        val params = interfaces[named.name]?.typeParams.orEmpty()
        return params.zip(named.args).toMap()
    }

    fun substitute(type: JsTypeRef, substitutions: Map<String, JsTypeRef>): JsTypeRef = when {
        substitutions.isEmpty() -> type
        type is JsTypeRef.Named && type.args.isEmpty() -> substitutions[type.name] ?: type
        type is JsTypeRef.Named -> JsTypeRef.Named(type.name, type.args.map { substitute(it, substitutions) })
        type is JsTypeRef.Union -> JsTypeRef.Union(type.members.map { substitute(it, substitutions) }, type.intersection)
        else -> type
    }

    private fun expand(type: JsTypeRef, depth: Int = 0): JsTypeRef {
        if (aliases.isEmpty() || depth > MAX_ALIAS_DEPTH) return type
        return when (type) {
            is JsTypeRef.Named -> aliases[type.name]?.let { expand(JsTypeRef.parse(it), depth + 1) }
                ?: type.copy(args = type.args.map { expand(it, depth + 1) })
            is JsTypeRef.Union -> type.copy(members = type.members.map { expand(it, depth + 1) })
            JsTypeRef.Unknown -> type
        }
    }

    private companion object { const val MAX_ALIAS_DEPTH = 8 }
}
