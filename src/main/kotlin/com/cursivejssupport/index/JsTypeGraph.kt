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
            if (name != CALL_MEMBER && !name.startsWith(INDEX_MEMBER_PREFIX)) {
                out.putIfAbsent(name, JsResolvedMember(type, name, overloads, distance))
            }
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

    fun memberType(member: JsMember): JsTypeRef {
        val type = expand(JsTypeRef.parse(if (member.kind == "method") member.returns else member.type))
        return if (member.kind != "method" && member.optional) {
            when (type) {
                is JsTypeRef.Union -> type.copy(members = (type.members + JsTypeRef.Named("undefined")).distinct())
                else -> JsTypeRef.Union(listOf(type, JsTypeRef.Named("undefined")))
            }
        } else type
    }

    fun membersOf(type: JsTypeRef): Map<String, JsResolvedMember> {
        if (type is JsTypeRef.Union) {
            val branches = type.members.mapNotNull { member ->
                member.primaryNamed()?.let { named -> members(named.name).takeIf { it.isNotEmpty() } }
            }
            if (branches.isEmpty()) return emptyMap()
            val names = if (type.intersection) branches.flatMap { it.keys }.toSet()
            else branches.map { it.keys.toSet() }.reduce { common, keys -> common intersect keys }
            return names.associateWith { name ->
                val hits = branches.mapNotNull { it[name] }
                val first = hits.first()
                val overloads = hits.flatMap { it.overloads }.distinct()
                val merged = if (!type.intersection && overloads.all { it.kind == "property" }) {
                    listOf(overloads.first().copy(type = overloads.map { it.type }.distinct().joinToString(" | ")))
                } else overloads
                first.copy(overloads = merged)
            }
        }
        val preferred = type.primaryNamed() ?: return emptyMap()
        val direct = members(preferred.name)
        return direct
    }

    fun substitution(type: JsTypeRef): Map<String, JsTypeRef> {
        val named = type.primaryNamed() ?: return emptyMap()
        val params = interfaces[named.name]?.typeParams.orEmpty()
        return params.zip(named.args).toMap()
    }

    fun callSignatures(type: JsTypeRef): List<JsMember> = signatures(type) { it.members[CALL_MEMBER].orEmpty() }

    fun constructSignatures(type: JsTypeRef): List<JsMember> = signatures(type) { it.members[CONSTRUCT_MEMBER].orEmpty() }

    fun indexedValue(type: JsTypeRef, numeric: Boolean): JsTypeRef? {
        val named = type.primaryNamed() ?: return null
        val definition = interfaces[named.name] ?: return null
        val key = if (numeric) "${INDEX_MEMBER_PREFIX}number" else "${INDEX_MEMBER_PREFIX}string"
        val member = definition.members[key]?.firstOrNull()
            ?: definition.members["${INDEX_MEMBER_PREFIX}string"]?.firstOrNull()
            ?: return null
        return substitute(memberType(member), substitution(type))
    }

    private fun signatures(type: JsTypeRef, select: (JsInterface) -> List<JsMember>): List<JsMember> {
        val named = type.primaryNamed() ?: return emptyList()
        val substitutions = substitution(type)
        return select(interfaces[named.name] ?: return emptyList()).map { signature ->
            signature.copy(
                params = signature.params.map { it.copy(type = substitute(JsTypeRef.parse(it.type), substitutions).display()) },
                returns = substitute(JsTypeRef.parse(signature.returns), substitutions).display(),
            )
        }
    }

    fun substitute(type: JsTypeRef, substitutions: Map<String, JsTypeRef>): JsTypeRef = when {
        substitutions.isEmpty() -> type
        type is JsTypeRef.Named && type.args.isEmpty() -> substitutions[type.name] ?: type
        type is JsTypeRef.Named -> JsTypeRef.Named(type.name, type.args.map { substitute(it, substitutions) })
        type is JsTypeRef.Union -> JsTypeRef.Union(type.members.map { substitute(it, substitutions) }, type.intersection)
        type is JsTypeRef.Record -> type.copy(
            properties = type.properties.mapValues { substitute(it.value, substitutions) },
            indexValue = type.indexValue?.let { substitute(it, substitutions) },
        )
        else -> type
    }

    private fun expand(type: JsTypeRef, depth: Int = 0): JsTypeRef {
        if (aliases.isEmpty() || depth > MAX_ALIAS_DEPTH) return type
        return when (type) {
            is JsTypeRef.Named -> aliases[type.name]?.let { expand(JsTypeRef.parse(it), depth + 1) }
                ?: type.copy(args = type.args.map { expand(it, depth + 1) })
            is JsTypeRef.Union -> type.copy(members = type.members.map { expand(it, depth + 1) })
            is JsTypeRef.Record -> type.copy(
                properties = type.properties.mapValues { expand(it.value, depth + 1) },
                indexValue = type.indexValue?.let { expand(it, depth + 1) },
            )
            JsTypeRef.Unknown -> type
        }
    }

    private companion object {
        const val MAX_ALIAS_DEPTH = 8
        const val CALL_MEMBER = "${'$'}call"
        const val CONSTRUCT_MEMBER = "new"
        const val INDEX_MEMBER_PREFIX = "${'$'}index:"
    }
}
