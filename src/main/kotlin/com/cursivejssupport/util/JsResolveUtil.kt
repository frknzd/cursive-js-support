package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol

object JsResolveUtil {

    /**
     * Attempts to infer the JS type of a ClojureScript expression.
     * Supports:
     *  - js/Global constants (e.g., js/document → "Document")
     *  - Chained method calls (e.g., (.createRange js/document) → "Range")
     *  - Type hints (^js/HTMLElement el → "HTMLElement")
     *  - Local bindings if their initializer type can be inferred.
     */
    fun resolveType(element: PsiElement?, index: JsSymbolIndex, depth: Int = 0): String? {
        if (element == null || depth > 5) return null
        JsInteropPsi.jsQualifiedSymbolText(element)?.let { full ->
            val segments = JsInteropChain.segmentsFromFullText(full)
            if (segments != null && segments.isNotEmpty()) {
                index.resolveJsChainType(segments)?.let { return it }
            }
        }
        
        return when (element) {
            is ClEditorSymbol -> {
                val full = element.text ?: ""
                if (full.startsWith("js/")) {
                    val segments = JsInteropChain.segmentsFromFullText(full)
                    if (segments != null && segments.isNotEmpty()) {
                        index.resolveJsChainType(segments)?.let { return it }
                    }
                }

                // 1. Direct js/Global access (namespace split form)
                if (element.namespace == "js") {
                    val name = element.name ?: ""
                    if ('.' in name) {
                        val segments = name.split('.').map { it.trim() }.filter { it.isNotEmpty() }
                        if (segments.isNotEmpty()) {
                            val t = index.resolveJsChainType(segments)
                            if (t != null) return t
                        }
                    }
                    val type = index.resolveGlobalType(name)
                    if (type != null) return type

                    // Fallback: if it's a known function, we might not have a "type" but we can infer 'function'
                    if (index.resolveFunctions(name) != null) return "Function"
                }
                
                // 2. js global itself
                if (element.text == "js") return "Window"

                // 2. Type hint on the symbol itself
                val hint = findTypeHint(element)
                if (hint != null) return sanitizeType(hint)

                // 3. Resolve to definition and check its type
                val definition = resolveDefinition(element)
                if (definition != null && definition != element) {
                    // Check hint on definition (e.g., function param [^js/Type el])
                    val defHint = findTypeHint(definition)
                    if (defHint != null) return sanitizeType(defHint)
                    
                    // If it's a let-binding, try to resolve the type of its initializer
                    val initializer = findBindingInitializer(definition)
                    if (initializer != null) {
                        return resolveType(initializer, index, depth + 1)
                    }
                }
                null
            }
            is ClList -> {
                val head = getHead(element)
                if (head is ClEditorSymbol && head.text == "set!") {
                    val children = element.children.filter { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")" }
                    val valueArg = children.getOrNull(2)
                    if (valueArg != null) return resolveType(valueArg, index, depth + 1)
                    return null
                }
                if (head is ClEditorSymbol) {
                    val ht = head.text
                    if (ht == "goog.object/get" || ht == "js/goog.object.get") {
                        val children = element.children.filter { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")" }
                        val obj = children.getOrNull(1)
                        if (obj != null) return resolveType(obj, index, depth + 1)
                    }
                }
                // Method call: (.method receiver ...)
                if (head is ClEditorSymbol && head.text.startsWith(".")) {
                    val memberName = head.text.removePrefix(".").removePrefix("-")
                    val receiver = getSecondElement(element)
                    val receiverType = resolveType(receiver, index, depth + 1) ?: return null

                    val member = index.resolveMember(receiverType, memberName)?.first
                    return if (member != null) {
                        if (member.kind == "method") member.returns else member.type
                    } else null
                }
                null
            }
            else -> null
        }
    }

    private fun resolveDefinition(symbol: ClEditorSymbol): PsiElement? {
        val ref = symbol.reference
        if (ref != null) {
            if (ref is PsiPolyVariantReference) {
                ref.multiResolve(false).firstOrNull()?.element?.let { return it }
            } else {
                ref.resolve()?.let { return it }
            }
        }
        return lexicalDefInitializer(symbol)
    }

    /**
     * When references are not yet available (e.g. during incomplete typing), resolve simple `def` / `defonce` init.
     */
    private fun lexicalDefInitializer(symbol: ClEditorSymbol): PsiElement? {
        val list = PsiTreeUtil.getParentOfType(symbol, ClList::class.java, true) ?: return null
        val head = getHead(list) as? ClEditorSymbol ?: return null
        if (head.text != "def" && head.text != "defonce") return null
        val children = list.children.filter { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")" }
        if (children.size >= 3 && children[1] === symbol) {
            return children.getOrNull(2)
        }
        return null
    }

    private fun findBindingInitializer(definition: PsiElement): PsiElement? {
        val parent = definition.parent ?: return null
        if (!parent.javaClass.simpleName.contains("ClVector")) return null
        val list = parent.parent as? ClList ?: return null
        val listHead = getHead(list) as? ClEditorSymbol ?: return null
        if (!isLetLikeBindingHead(listHead.text)) return null
        val children = parent.children.filter { it !is PsiWhiteSpace && it !is PsiComment }
        val idx = children.indexOf(definition)
        if (idx >= 0 && idx + 1 < children.size) {
            return children[idx + 1]
        }
        return null
    }

    private fun isLetLikeBindingHead(head: String): Boolean =
        when (head) {
            "let", "let*", "binding", "if-let", "if-let*", "when-let", "when-let*", "when-some" -> true
            else -> false
        }

    private fun getHead(list: ClList): PsiElement? {
        return list.children.firstOrNull { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" }
    }

    private fun getSecondElement(list: ClList): PsiElement? {
        val children = list.children.filter { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" }
        return children.getOrNull(1)
    }

    private fun findTypeHint(element: PsiElement): String? {
        // 1. Check for ^Type metadata on the element or its parent
        // In Cursive, metadata often appears as a sibling with '^'
        var prev = element.prevSibling
        while (prev != null && (prev is PsiWhiteSpace || prev is PsiComment)) {
            prev = prev.prevSibling
        }
        
        if (prev != null && prev.text.startsWith("^")) {
            return prev.text.removePrefix("^")
        }

        // 2. Check if the element is inside a metadata form
        // Cursive might wrap elements in a metadata-carrying PSI node
        var current = element.parent
        repeat(3) {
            if (current == null) return@repeat
            if (current!!.text.startsWith("^")) {
                val hint = current!!.text.split(Regex("\\s+")).firstOrNull()?.removePrefix("^")
                if (hint != null) return hint
            }
            current = current!!.parent
        }

        return null
    }

    private fun sanitizeType(type: String): String? {
        val s = type.removePrefix("js/").removePrefix("cljs.core/")
        return if (s == "js" || s == "any" || s == "Object" || s == "object") null else s
    }
}
