package com.cursivejssupport.index

import java.util.concurrent.ConcurrentHashMap

/**
 * Structured view of a TypeScript type string as stored in the index.
 *
 * The extractor keeps types string-encoded (`Promise<Response>`, `Node|null`, `Element[]`,
 * `Window&any`) so the JSON schema stays additive; [parse] turns that wire form back into a
 * structure. Arrays normalize to `Named("Array", [element])` so `Element[]` resolves through
 * the `Array` interface instead of dead-ending on a name lookup.
 */
sealed interface JsTypeRef {

    data class Named(val name: String, val args: List<JsTypeRef> = emptyList()) : JsTypeRef

    data class Union(val members: List<JsTypeRef>, val intersection: Boolean = false) : JsTypeRef

    data class Record(
        val properties: Map<String, JsTypeRef>,
        val optional: Set<String> = emptySet(),
        val methods: Set<String> = emptySet(),
        val indexValue: JsTypeRef? = null,
    ) : JsTypeRef

    object Unknown : JsTypeRef {
        override fun toString(): String = "Unknown"
    }

    /** Human-readable form: `Promise<Response>`, `Element[]`, `Node | null`. */
    fun display(): String = when (this) {
        is Named -> when {
            name == "Tuple" -> args.joinToString(", ", "[", "]") { it.display() }
            name == "Array" && args.size == 1 -> {
                val el = args[0]
                if (el is Union) "(${el.display()})[]" else "${el.display()}[]"
            }
            args.isEmpty() -> name
            else -> "$name<${args.joinToString(", ") { it.display() }}>"
        }
        is Union -> members.joinToString(if (intersection) " & " else " | ") { it.display() }
        is Record -> buildList {
            properties.forEach { (name, type) ->
                add(if (name in methods) "$name(...): ${type.display()}"
                else "$name${if (name in optional) "?" else ""}: ${type.display()}")
            }
            indexValue?.let { add("[key: string]: ${it.display()}") }
        }.joinToString("; ", "{ ", " }")
        Unknown -> "any"
    }

    /**
     * The single interface name to resolve members against — the canonical-type contract.
     *
     * Union/intersection members are flattened and preferred in two passes: a concrete
     * interface name over primitives / meta-types first (so `"absolute"|CSSPositionValue`
     * yields the interface, not `string`), then any non-trivial part (`string|null` →
     * `string`). Generic args are stripped (`Promise<Response>` → `Promise`); arrays map
     * to `Array`. Empty when nothing usable is present.
     */
    fun primaryName(): String = primaryNamed()?.name ?: leafNames().firstOrNull().orEmpty()

    /** The [Named] leaf [primaryName] refers to — carries the generic args for substitution. */
    fun primaryNamed(): Named? {
        val leaves = leafNameds()
        return leaves.firstOrNull { it.name.isNotEmpty() && it.name !in TRIVIAL_TYPES && it.name !in PRIMITIVE_TYPES }
            ?: leaves.firstOrNull { it.name.isNotEmpty() && it.name !in TRIVIAL_TYPES }
    }

    /** All named leaves in declaration order, flattening nested unions/intersections. */
    fun leafNameds(): List<Named> = when (this) {
        is Named -> listOf(this)
        is Union -> members.flatMap { it.leafNameds() }
        is Record -> listOf(Named("Object"))
        Unknown -> emptyList()
    }

    private fun leafNames(): List<String> = leafNameds().map { it.name }

    companion object {
        val TRIVIAL_TYPES = setOf("any", "null", "undefined", "never", "unknown", "void")
        val PRIMITIVE_TYPES = setOf("string", "number", "boolean", "bigint", "symbol", "object")

        private const val CACHE_CAP = 10_000
        private val cache = ConcurrentHashMap<String, JsTypeRef>()

        /**
         * Parses the wire form: names, `<…>` generic args, `[]` array suffixes, `|` unions,
         * `&` intersections, and parenthesized groups. Never throws — malformed input yields
         * [Unknown].
         */
        fun parse(raw: String): JsTypeRef {
            val key = raw.trim()
            if (key.isEmpty()) return Unknown
            cache[key]?.let { return it }
            val parsed = try {
                val p = Parser(key)
                val t = p.parseUnion()
                if (p.atEnd()) t else Unknown
            } catch (_: Exception) {
                Unknown
            }
            if (cache.size < CACHE_CAP) cache[key] = parsed
            return parsed
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean {
            skipWs()
            return i >= s.length
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        fun parseUnion(): JsTypeRef {
            val first = parseIntersection()
            skipWs()
            if (peek() != '|') return first
            val members = mutableListOf(first)
            while (peek() == '|') {
                i++
                members.add(parseIntersection())
                skipWs()
            }
            return Union(members)
        }

        private fun parseIntersection(): JsTypeRef {
            val first = parsePostfix()
            skipWs()
            if (peek() != '&') return first
            val members = mutableListOf(first)
            while (peek() == '&') {
                i++
                members.add(parsePostfix())
                skipWs()
            }
            return Union(members, intersection = true)
        }

        private fun parsePostfix(): JsTypeRef {
            var t = parsePrimary()
            skipWs()
            while (peek() == '[') {
                i++
                skipWs()
                if (peek() != ']') throw IllegalArgumentException("unclosed []")
                i++
                t = Named("Array", listOf(t))
                skipWs()
            }
            return t
        }

        private fun parsePrimary(): JsTypeRef {
            skipWs()
            if (peek() == '[') {
                i++
                skipWs()
                val entries = mutableListOf<JsTypeRef>()
                if (peek() != ']') {
                    entries += parseUnion()
                    skipWs()
                    while (peek() == ',') {
                        i++
                        entries += parseUnion()
                        skipWs()
                    }
                }
                if (peek() != ']') throw IllegalArgumentException("unclosed tuple")
                i++
                return Named("Tuple", entries)
            }
            if (peek() == '{') return parseRecord()
            if (peek() == '(') {
                i++
                val inner = parseUnion()
                skipWs()
                if (peek() != ')') throw IllegalArgumentException("unclosed ()")
                i++
                return inner
            }
            val start = i
            while (i < s.length && s[i] !in DELIMITERS) i++
            val name = s.substring(start, i).trim().removePrefix("readonly ")
            if (name.isEmpty()) throw IllegalArgumentException("empty name")
            skipWs()
            if (peek() != '<') return Named(name)
            i++
            val args = mutableListOf(parseUnion())
            skipWs()
            while (peek() == ',') {
                i++
                args.add(parseUnion())
                skipWs()
            }
            if (peek() != '>') throw IllegalArgumentException("unclosed <>")
            i++
            return Named(name, args)
        }

        private fun parseRecord(): JsTypeRef {
            i++
            val properties = linkedMapOf<String, JsTypeRef>()
            val optional = linkedSetOf<String>()
            val methods = linkedSetOf<String>()
            var indexValue: JsTypeRef? = null
            while (true) {
                skipRecordSeparators()
                if (peek() == '}') { i++; break }
                if (peek() == null) throw IllegalArgumentException("unclosed record")
                if (peek() == '[') {
                    while (peek() != null && peek() != ']') i++
                    if (peek() == ']') i++
                    skipWs()
                    if (peek() != ':') throw IllegalArgumentException("invalid index signature")
                    i++
                    indexValue = parseUnion()
                    continue
                }
                val start = i
                while (i < s.length && s[i] !in charArrayOf('?', ':', '(', '}', ';', ',')) i++
                val name = s.substring(start, i).trim().removePrefix("readonly ").removeSurrounding("\"")
                if (name.isEmpty()) throw IllegalArgumentException("empty record member")
                if (peek() == '?') { optional += name; i++ }
                skipWs()
                if (peek() == '(') {
                    methods += name
                    skipBalanced('(', ')')
                    skipWs()
                    if (peek() == ':') { i++; properties[name] = parseUnion() }
                    continue
                }
                if (peek() != ':') throw IllegalArgumentException("invalid record member")
                i++
                properties[name] = parseUnion()
            }
            return Record(properties, optional, methods, indexValue)
        }

        private fun skipRecordSeparators() {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ';' || s[i] == ',')) i++
        }

        private fun skipBalanced(open: Char, close: Char) {
            if (peek() != open) return
            var depth = 0
            while (i < s.length) {
                if (s[i] == open) depth++ else if (s[i] == close && --depth == 0) { i++; return }
                i++
            }
        }

        companion object {
            private const val DELIMITERS = "<>[]{}|&(),;:"
        }
    }
}
