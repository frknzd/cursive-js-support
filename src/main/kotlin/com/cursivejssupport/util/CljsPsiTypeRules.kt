package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.index.JsTypeRef
import com.cursivejssupport.npm.NpmBinding
import com.cursivejssupport.npm.NpmBindingKind
import com.cursivejssupport.npm.NsAliasResolver
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol

internal object CljsPsiTypeRules {
    fun literalType(element: PsiElement): TypeResolution? {
        val text = element.text.trim()
        JsTypeFlow.jsLiteral(text)?.let {
            return TypeResolution(it.type, true, InferenceProvenance.LEXICAL_BINDING, it.returnMembers)
        }
        val type = when {
            text == "nil" || text == "js/undefined" -> "null"
            text == "true" || text == "false" -> "boolean"
            text.matches(Regex("[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")) -> "number"
            text.length >= 2 && text.first() == '"' && text.last() == '"' -> "string"
            else -> return null
        }
        return TypeResolution(JsTypeRef.Named(type), true)
    }

    fun npmBinding(file: PsiFile, symbol: String): NpmBinding? {
        val bindings = NsAliasResolver.resolveAliases(file)
        bindings[symbol]?.let { return it }
        val alias = symbol.substringBefore('/', missingDelimiterValue = "")
        val export = symbol.substringAfter('/', missingDelimiterValue = "")
        if (alias.isEmpty() || export.isEmpty()) return null
        val namespace = bindings[alias]?.takeIf { it.kind in setOf(NpmBindingKind.AS, NpmBindingKind.ALL, NpmBindingKind.RELATIVE) } ?: return null
        return NpmBinding(namespace.packageName, NpmBindingKind.REFER, export, relativeFilePath = namespace.relativeFilePath)
    }

    fun destructuredBindingType(
        definition: PsiElement,
        container: TypeResolution,
        index: JsSymbolIndex,
    ): JsTypeRef? {
        val ancestors = generateSequence(definition.parent) { it.parent }.take(8).toList()
        val pattern = ancestors.firstOrNull { candidate ->
            val binding = candidate.parent?.takeIf { it.javaClass.simpleName.contains("ClVector") } ?: return@firstOrNull false
            val form = binding.parent as? ClList ?: return@firstOrNull false
            (meaningfulChildren(form).firstOrNull() as? ClEditorSymbol)?.text in BINDING_HEADS
        } ?: return null
        return descendPattern(pattern, definition, container.ref, container.effectiveSemanticMembers, index)
    }

    private fun descendPattern(
        pattern: PsiElement,
        definition: PsiElement,
        type: JsTypeRef,
        semanticMembers: List<com.cursivejssupport.types.JsMemberDescriptor>,
        index: JsSymbolIndex,
    ): JsTypeRef? {
        if (pattern.javaClass.simpleName.contains("ClMap")) {
            val name = definition.text
            val renamedKey = Regex("(?:^|[\\s{])${Regex.escape(name)}\\s+(?::([\\w$-]+)|\"([^\"]+)\")")
                .find(pattern.text)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            val shorthand = Regex(":(?:keys|strs|syms)\\s*\\[[^]]*\\b${Regex.escape(name)}\\b").containsMatchIn(pattern.text)
            if (renamedKey != null || shorthand) {
                return propertyType(type, semanticMembers, renamedKey ?: name, index)
            }
            val entries = meaningfulPatternChildren(pattern)
            val nested = entries.firstOrNull { it !== definition && PsiTreeUtil.isAncestor(it, definition, false) }
            if (nested != null) {
                val key = entries.getOrNull(entries.indexOf(nested) + 1)?.text?.removePrefix(":")?.removeSurrounding("\"")
                    ?: return null
                val nestedType = propertyType(type, semanticMembers, key, index) ?: return null
                return descendPattern(nested, definition, nestedType, emptyList(), index)
            }
            return propertyType(type, semanticMembers, name, index)
        }
        val bindings = meaningfulPatternChildren(pattern).filter {
            it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("[", "]", "&") && !it.text.startsWith(":")
        }
        val child = bindings.firstOrNull { it === definition || PsiTreeUtil.isAncestor(it, definition, false) } ?: return null
        val position = bindings.indexOf(child)
        val named = type as? JsTypeRef.Named
        val childType = if (named?.name == "Tuple") named.args.getOrNull(position) else collectionElementType(type)
        return if (child === definition) childType else childType?.let {
            descendPattern(child, definition, it, emptyList(), index)
        }
    }

    private fun propertyType(
        type: JsTypeRef,
        semanticMembers: List<com.cursivejssupport.types.JsMemberDescriptor>,
        key: String,
        index: JsSymbolIndex,
    ): JsTypeRef? = semanticMembers.firstOrNull { it.name == key }?.let { JsTypeRef.parse(it.type) }
        ?: index.resolveMembersOf(type)[key]?.first?.let(index::memberValueType)
        ?: index.resolveIndexedValueType(type, numeric = false)

    private fun meaningfulPatternChildren(pattern: PsiElement): List<PsiElement> = pattern.children.filter {
        it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("[", "]", "{", "}")
    }

    fun narrow(symbol: ClEditorSymbol, resolution: TypeResolution, index: JsSymbolIndex): TypeResolution {
        var current: PsiElement? = symbol
        repeat(8) {
            val form = current?.parent as? ClList ?: run { current = current?.parent; return@repeat }
            val children = meaningfulChildren(form)
            val head = (children.firstOrNull() as? ClEditorSymbol)?.text
            if (head in setOf("if", "if-not", "when", "when-not") && children.size >= 3) {
                narrowedInConditional(symbol, resolution, head, children, index)?.let { return it }
            }
            current = form
        }
        return resolution
    }

    fun findTypeHint(element: PsiElement): String? {
        var prev = element.prevSibling
        while (prev != null && (prev is PsiWhiteSpace || prev is PsiComment)) prev = prev.prevSibling
        if (prev != null && prev.text.startsWith("^")) return prev.text.removePrefix("^")
        var current = element.parent
        repeat(3) {
            if (current?.text?.startsWith("^") == true) {
                current?.text?.split(Regex("\\s+"))?.firstOrNull()?.removePrefix("^")?.let { return it }
            }
            current = current?.parent
        }
        return null
    }

    fun sanitizeType(type: String): String? {
        val value = type.removePrefix("js/").removePrefix("cljs.core/")
        return value.takeUnless { it in setOf("js", "any", "Object", "object") }
    }

    private fun narrowedInConditional(
        symbol: ClEditorSymbol,
        resolution: TypeResolution,
        head: String?,
        children: List<PsiElement>,
        index: JsSymbolIndex,
    ): TypeResolution? {
        val condition = children[1]
        val inThen = PsiTreeUtil.isAncestor(children[2], symbol, false)
        val inElse = children.getOrNull(3)?.let { PsiTreeUtil.isAncestor(it, symbol, false) } == true
        if (!inThen && !inElse) return null
        val positive = (inThen && head !in setOf("if-not", "when-not")) ||
            (inElse && head in setOf("if-not", "when-not"))
        if (condition.text == symbol.text && positive) return withoutNullish(resolution)
        val parts = (condition as? ClList)?.let(::meaningfulChildren).orEmpty()
        val predicate = (parts.firstOrNull() as? ClEditorSymbol)?.text
        if (parts.getOrNull(1)?.text == symbol.text &&
            (predicate in setOf("some?", "cljs.core/some?") && positive ||
                predicate in setOf("nil?", "cljs.core/nil?") && !positive)) return withoutNullish(resolution)
        if (predicate in setOf("instance?", "cljs.core/instance?") && positive && parts.getOrNull(2)?.text == symbol.text) {
            val type = parts.getOrNull(1)?.text?.removePrefix("js/")
            if (!type.isNullOrBlank()) return resolution.copy(ref = JsTypeRef.Named(type),
                provenance = InferenceProvenance.CONTROL_FLOW)
        }
        if (positive && condition.text.contains("js/typeof") && condition.text.contains(symbol.text)) {
            val primitive = listOf("string", "number", "boolean", "bigint", "symbol", "function")
                .firstOrNull { condition.text.contains("\"$it\"") }
            if (primitive != null) return resolution.copy(ref = JsTypeRef.Named(primitive),
                provenance = InferenceProvenance.CONTROL_FLOW)
        }
        discriminatedUnion(symbol, resolution.ref, condition.text, positive, index)?.let {
            return resolution.copy(ref = it, provenance = InferenceProvenance.CONTROL_FLOW)
        }
        return null
    }

    private fun discriminatedUnion(
        symbol: ClEditorSymbol,
        type: JsTypeRef,
        condition: String,
        positive: Boolean,
        index: JsSymbolIndex,
    ): JsTypeRef? {
        val union = type as? JsTypeRef.Union ?: return null
        val member = Regex("\\.\\-([\\w${'$'}-]+)\\s+${Regex.escape(symbol.text)}").find(condition)?.groupValues?.get(1)
            ?: return null
        val literal = Regex("\"([^\"]+)\"").find(condition)?.groupValues?.get(1) ?: return null
        val selected = union.members.filter { branch ->
            val declared = index.resolveMembersOf(branch)[member]?.first?.type?.trim()?.removeSurrounding("\"")
            (declared == literal) == positive
        }
        return selected.takeIf { it.isNotEmpty() }?.let(JsTypeFlow::union)
    }

    private fun withoutNullish(resolution: TypeResolution) = resolution.copy(
        ref = JsTypeFlow.removeNullish(resolution.ref), provenance = InferenceProvenance.CONTROL_FLOW,
    )

    private fun meaningfulChildren(list: ClList): List<PsiElement> = list.children.filter {
        it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("(", ")")
    }

    private val BINDING_HEADS = setOf(
        "let", "let*", "binding", "if-let", "if-let*", "when-let", "when-let*", "when-some", "if-some",
        "loop", "loop*", "doseq",
    )
}
