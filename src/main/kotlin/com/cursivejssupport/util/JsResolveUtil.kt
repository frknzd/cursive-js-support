package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.index.JsTypeRef
import com.cursivejssupport.npm.NpmBindingKind
import com.cursivejssupport.npm.NsAliasResolver
import com.cursivejssupport.semantic.InteropSemanticService
import com.cursivejssupport.types.JsMemberDescriptor
import com.cursivejssupport.types.JsCallSignature
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.openapi.components.service
import com.intellij.psi.util.PsiTreeUtil
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Result of type inference for a ClojureScript expression.
 *
 * [confident] is false when the resolution leaned on a heuristic that may not reflect the real
 * runtime type (currently: lexical `def` scraping during incomplete typing). Confident results
 * let goto-declaration jump directly instead of offering alternatives.
 */
enum class InferenceProvenance { DECLARATION, TYPE_HINT, LEXICAL_BINDING, CONTROL_FLOW, CALL_SIGNATURE, HEURISTIC }

data class TypeResolution(
    val ref: JsTypeRef,
    val confident: Boolean,
    val provenance: InferenceProvenance = InferenceProvenance.DECLARATION,
    val semanticMembers: List<JsMemberDescriptor> = emptyList(),
    val semanticCallSignatures: List<JsCallSignature> = emptyList(),
) {
    val name: String get() = ref.primaryName()
    val confidence: Double get() = if (confident) 1.0 else 0.5
}

object JsResolveUtil {

    /**
     * Attempts to infer the JS type of a ClojureScript expression.
     * Supports:
     *  - js/Global constants (e.g., js/document → "Document")
     *  - Chained method calls (e.g., (.createRange js/document) → "Range")
     *  - Chain macros: (.. root step…), (-> root step…), (some-> root step…), (doto root step…)
     *  - Type hints (^js/HTMLElement el → "HTMLElement")
     *  - Local bindings if their initializer type can be inferred
     *  - defn return types (last body expression) and calls to such defns.
     */
    fun resolveType(element: PsiElement?, index: JsSymbolIndex, depth: Int = 0): String? =
        resolveTypeRef(element, index, depth)?.name?.takeIf { it.isNotEmpty() }

    /** Generic-aware twin of [resolveType], carrying the full [JsTypeRef] and a confidence flag. */
    fun resolveTypeRef(element: PsiElement?, index: JsSymbolIndex, depth: Int = 0): TypeResolution? =
        resolve(element, index, State(depth))

    /** Recursion state: depth cap plus an identity set of visited definition sites (def→def cycles). */
    private class State(var depth: Int) {
        val visitedDefinitions: MutableSet<PsiElement> =
            Collections.newSetFromMap(IdentityHashMap())
    }

    private const val MAX_DEPTH = 8

    private fun resolve(element: PsiElement?, index: JsSymbolIndex, state: State): TypeResolution? {
        if (element == null || state.depth > MAX_DEPTH) return null
        state.depth++
        try {
            return resolveInner(element, index, state)
        } finally {
            state.depth--
        }
    }

    private fun named(
        name: String,
        confident: Boolean = true,
        provenance: InferenceProvenance = InferenceProvenance.DECLARATION,
    ): TypeResolution = TypeResolution(JsTypeRef.Named(name), confident, provenance)

    private fun union(resolutions: List<TypeResolution>): TypeResolution? {
        if (resolutions.isEmpty()) return null
        val refs = resolutions.flatMap { r ->
            when (val ref = r.ref) {
                is JsTypeRef.Union -> ref.members
                else -> listOf(ref)
            }
        }.distinct()
        return TypeResolution(
            if (refs.size == 1) refs.first() else JsTypeRef.Union(refs),
            resolutions.all { it.confident },
            InferenceProvenance.CONTROL_FLOW,
        )
    }

    private fun resolveInner(element: PsiElement, index: JsSymbolIndex, state: State): TypeResolution? {
        // 1. Constructor call (Fuse. ...) -> instance type
        if (element is ClList) {
            val head = getHead(element)
            if (head is ClEditorSymbol) {
                val ht = head.text ?: ""
                if (ht.endsWith(".") && ht.length > 1 && !ht.startsWith(".")) {
                    return named(ht.removeSuffix("."))
                }
            }
        }

        JsInteropPsi.jsQualifiedSymbolText(element)?.let { full ->
            val segments = JsInteropChain.segmentsFromFullText(full)
            if (segments != null && segments.isNotEmpty()) {
                index.resolveJsChainTypeRef(segments)?.let { return TypeResolution(it, confident = true) }
            }
        }

        return when (element) {
            is ClEditorSymbol -> resolveSymbol(element, index, state)
            is ClList -> resolveList(element, index, state)
            else -> null
        }
    }

    private fun resolveSymbol(element: ClEditorSymbol, index: JsSymbolIndex, state: State): TypeResolution? {
        val full = element.text ?: ""

        // 2. NPM alias type resolution — depends on the binding kind
        val file = element.containingFile
        if (file != null) {
            val binding = NsAliasResolver.resolveAliases(file)[full]
            if (binding != null) {
                val semanticExport = when (binding.kind) {
                    NpmBindingKind.REFER -> binding.exportName ?: full
                    NpmBindingKind.DEFAULT -> "default"
                    NpmBindingKind.AS, NpmBindingKind.ALL -> null
                }
                if (semanticExport != null) {
                    file.project.service<InteropSemanticService>().exportType(file, binding.packageName, semanticExport)?.let {
                        return TypeResolution(
                            it.type, it.confidence >= 0.9, InferenceProvenance.DECLARATION,
                            it.members, it.callSignatures,
                        )
                    }
                }
                val type = when (binding.kind) {
                    NpmBindingKind.REFER -> {
                        // :refer [exportName] — resolve the specific named export's type
                        index.resolveNpmExportType(binding.packageName, binding.exportName ?: full)
                    }
                    NpmBindingKind.DEFAULT -> {
                        // :default Alias — resolve the default export's type, with global fallback
                        var t = index.resolveNpmExportType(binding.packageName, "default")
                        if (t == null || t == "any" || t == "object") {
                            val possibleNamespace = binding.packageName.split('-').joinToString("") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() } }
                            if (index.isKnownGlobal(possibleNamespace)) t = index.resolveGlobalType(possibleNamespace)
                            if (t == null) {
                                val camelNamespace = binding.packageName.split('-').mapIndexed { i, s ->
                                    if (i == 0) s else s.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
                                }.joinToString("")
                                if (index.isKnownGlobal(camelNamespace)) t = index.resolveGlobalType(camelNamespace)
                            }
                        }
                        t
                    }
                    NpmBindingKind.AS, NpmBindingKind.ALL -> {
                        // :as/:all Alias — the alias is a module namespace object, not a typed value.
                        // Type inference via dot forms on the alias itself is not meaningful.
                        null
                    }
                }
                if (type != null) return TypeResolution(JsTypeRef.parse(type), confident = true)
            }
        }

        if (full.startsWith("js/")) {
            val segments = JsInteropChain.segmentsFromFullText(full)
            if (segments != null && segments.isNotEmpty()) {
                index.resolveJsChainTypeRef(segments)?.let { return TypeResolution(it, confident = true) }
            }
        }

        // 3. Direct js/Global access (namespace split form)
        if (element.namespace == "js") {
            val name = element.name ?: ""
            if ('.' in name) {
                val segments = name.split('.').map { it.trim() }.filter { it.isNotEmpty() }
                if (segments.isNotEmpty()) {
                    index.resolveJsChainTypeRef(segments)?.let { return TypeResolution(it, confident = true) }
                }
            }
            val type = index.resolveGlobalType(name)
            if (type != null) return TypeResolution(JsTypeRef.parse(type), confident = true)

            // Fallback: if it's a known function, we might not have a "type" but we can infer 'function'
            if (index.resolveFunctions(name) != null) return named("Function")
        }

        // 4. js global itself
        if (element.text == "js") return named("Window")

        // 5. Type hint on the symbol itself
        val hint = findTypeHint(element)
        if (hint != null) sanitizeType(hint)?.let { return named(it, provenance = InferenceProvenance.TYPE_HINT) }

        // 6. Resolve to definition and check its type
        val definition = resolveDefinition(element)
        if (definition != null && definition !== element) {
            if (!state.visitedDefinitions.add(definition)) return null
            try {
                // Check hint on definition (e.g., function param [^js/Type el])
                val defHint = findTypeHint(definition)
                if (defHint != null) sanitizeType(defHint)?.let { return named(it, provenance = InferenceProvenance.TYPE_HINT) }

                // If it's a let-binding, try to resolve the type of its initializer
                val initializer = findBindingInitializer(definition)
                if (initializer != null) {
                    return resolve(initializer, index, state)?.copy(provenance = InferenceProvenance.LEXICAL_BINDING)
                }

                findDestructuredBindingInitializer(definition)?.let { destructured ->
                    val container = resolve(destructured, index, state) ?: return@let
                    val elementType = (container.ref as? JsTypeRef.Named)
                        ?.takeIf { it.name in setOf("Array", "ReadonlyArray", "NodeListOf", "HTMLCollectionOf") }
                        ?.args?.firstOrNull()
                    if (elementType != null) {
                        return TypeResolution(elementType, container.confident, InferenceProvenance.LEXICAL_BINDING)
                    }
                }

                resolveCallbackParameter(definition, index, state)?.let { return it }

                // defn name symbol: the symbol denotes a function, but a *call* resolves to the
                // return type — handled in resolveList. A bare reference has no member type.
                // def/defonce init is covered by resolveDefinition/lexicalDefInitializer paths:
                if (definition is ClEditorSymbol) {
                    lexicalDefInitializer(definition)?.let { init ->
                        return resolve(init, index, state)?.copy(confident = false)
                    }
                }
            } finally {
                state.visitedDefinitions.remove(definition)
            }
        }
        return null
    }

    private fun resolveList(element: ClList, index: JsSymbolIndex, state: State): TypeResolution? {
        val head = getHead(element)
        if (head is ClEditorSymbol && head.text == "set!") {
            val children = meaningfulChildren(element)
            val valueArg = children.getOrNull(2)
            if (valueArg != null) return resolve(valueArg, index, state)
            return null
        }
        if (head is ClEditorSymbol && head.text in setOf("if", "if-not", "if-let", "if-some")) {
            val branches = meaningfulChildren(element).drop(2).mapNotNull { resolve(it, index, state) }
            return union(branches)
        }
        if (head is ClEditorSymbol && head.text in setOf("cond", "condp", "case")) {
            val values = meaningfulChildren(element).drop(1)
                .filterIndexed { i, _ -> i % 2 == 1 }
                .mapNotNull { resolve(it, index, state) }
            return union(values)
        }
        if (head is ClEditorSymbol && head.text in setOf("first", "second", "last", "nth", "aget")) {
            val collection = meaningfulChildren(element).getOrNull(1)
            val resolved = resolve(collection, index, state)
            val elementType = (resolved?.ref as? JsTypeRef.Named)?.args?.firstOrNull()
            if (elementType != null) return TypeResolution(elementType, resolved.confident, InferenceProvenance.CALL_SIGNATURE)
        }
        if (head is ClEditorSymbol) {
            val ht = head.text
            if (ht == "goog.object/get" || ht == "js/goog.object.get") {
                val children = meaningfulChildren(element)
                val obj = children.getOrNull(1)
                if (obj != null) return resolve(obj, index, state)
            }
        }
        // Chain macros: (.. root step…), (-> root step…), (doto root step…), …
        val chainForm = InteropChains.parse(element)
        if (chainForm != null) {
            var childConfident = true
            val type = InteropChains.resolveFormType(chainForm, index) {
                val r = resolve(it, index, state)
                if (r != null && !r.confident) childConfident = false
                r?.name
            }
            return type?.let { named(it, confident = childConfident) }
        }
        // Method call: (.method receiver ...)
        if (head is ClEditorSymbol && head.text.startsWith(".")) {
            val memberName = head.text.removePrefix(".").removePrefix("-")
            val receiver = getSecondElement(element)
            val receiverType = resolve(receiver, index, state) ?: return null

            val callback = meaningfulChildren(element).getOrNull(2) as? ClList
            if (callback != null && memberName in setOf("then", "map", "flatMap")) {
                callbackReturnType(callback, index, state)?.let { callbackResult ->
                    val container = when (memberName) {
                        "then" -> "Promise"
                        else -> "Array"
                    }
                    return TypeResolution(
                        JsTypeRef.Named(container, listOf(callbackResult.ref)),
                        receiverType.confident && callbackResult.confident,
                        InferenceProvenance.CALL_SIGNATURE,
                    )
                }
            }

            val member = index.resolveMembersOf(receiverType.ref)[memberName]?.first ?: return null
            val projected = index.substitute(index.memberValueType(member), index.substitutionFor(receiverType.ref))
            if (projected == JsTypeRef.Unknown) return null
            return TypeResolution(projected, receiverType.confident)
        }
        // Body-wrapping forms: (do … expr), (let [b …] expr), (when test expr) — the form's
        // value is its last body expression.
        if (head is ClEditorSymbol && head.text in BODY_TAIL_HEADS) {
            val last = lastBodyExpr(element) ?: return null
            return resolve(last, index, state)
        }
        if (head is ClEditorSymbol && !head.text.startsWith(".")) {
            val binding = head.containingFile?.let { NsAliasResolver.resolveAliases(it)[head.text] }
            if (binding != null && binding.kind in setOf(NpmBindingKind.REFER, NpmBindingKind.DEFAULT)) {
                val export = binding.exportName ?: "default"
                val semantic = head.project.service<InteropSemanticService>()
                    .exportType(head.containingFile, binding.packageName, export)
                val semanticReturns = semantic?.callSignatures.orEmpty().map { it.returns }
                    .filter { it != JsTypeRef.Unknown }.distinct()
                val semanticReturnMembers = semantic?.callSignatures.orEmpty()
                    .flatMap { it.returnMembers }.distinctBy { it.name }
                if (semanticReturns.isNotEmpty() || semanticReturnMembers.isNotEmpty()) {
                    return TypeResolution(
                        when (semanticReturns.size) {
                            0 -> JsTypeRef.Unknown
                            1 -> semanticReturns.first()
                            else -> JsTypeRef.Union(semanticReturns)
                        },
                        semantic?.confidence?.let { it >= 0.9 } == true,
                        InferenceProvenance.CALL_SIGNATURE,
                        semanticReturnMembers,
                    )
                }
                val returns = index.resolveNpmExportMembers(binding.packageName, export).orEmpty()
                    .map { JsTypeRef.parse(it.returns) }
                    .filter { it != JsTypeRef.Unknown }
                    .distinct()
                if (returns.isNotEmpty()) {
                    return TypeResolution(
                        if (returns.size == 1) returns.first() else JsTypeRef.Union(returns),
                        true,
                        InferenceProvenance.CALL_SIGNATURE,
                    )
                }
            }
        }
        // Call to a named fn: (make-range …) — infer from the defn's return expression.
        if (head is ClEditorSymbol && !head.text.startsWith(".") && '/' !in head.text) {
            val definition = resolveDefinition(head)
            if (definition != null && definition !== head) {
                if (state.visitedDefinitions.add(definition)) {
                    try {
                        defnReturnType(definition, index, state)?.let { return it }
                    } finally {
                        state.visitedDefinitions.remove(definition)
                    }
                }
            }
        }
        return null
    }

    /**
     * When [definition] is the name symbol of `(defn name … [params] body…)`, infers the
     * function's return type: an explicit `^js/Type` hint on the name wins, otherwise the type
     * of the last body expression. Multi-arity defns union all inferred return types.
     */
    private fun defnReturnType(definition: PsiElement, index: JsSymbolIndex, state: State): TypeResolution? {
        val defnList = definition.parent as? ClList ?: return null
        val children = meaningfulChildren(defnList)
        val headText = (children.firstOrNull() as? ClEditorSymbol)?.text ?: return null
        if (headText !in DEFN_HEADS) return null
        if (children.getOrNull(1) !== definition &&
            JsInteropPsi.enclosingEditorSymbol(children.getOrNull(1) ?: return null) !== definition
        ) return null

        findTypeHint(definition)?.let { hint ->
            sanitizeType(hint)?.let { return named(it) }
        }

        // Single arity: (defn name [params] body…) — body is everything after the param vector.
        // Multi-arity: union the body tail of every arity.
        val afterName = children.drop(2).filter { it !is PsiComment }
        val paramVectorIdx = afterName.indexOfFirst { it.javaClass.simpleName.contains("ClVector") }
        if (paramVectorIdx >= 0) {
            val body = afterName.drop(paramVectorIdx + 1).lastOrNull() ?: return null
            return resolve(body, index, state)
        }
        val returns = afterName.filterIsInstance<ClList>().mapNotNull { arity ->
            meaningfulChildren(arity).lastOrNull()?.let { resolve(it, index, state) }
        }
        return union(returns)
    }

    /** Last body expression of a body-wrapping form, skipping the head and any binding vector. */
    private fun lastBodyExpr(list: ClList): PsiElement? {
        val children = meaningfulChildren(list)
        if (children.size < 2) return null
        return children.lastOrNull()?.takeIf { it !== children[0] }
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
        val children = meaningfulChildren(list)
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

    private fun findDestructuredBindingInitializer(definition: PsiElement): PsiElement? {
        var pattern: PsiElement? = definition.parent
        while (pattern != null && !pattern.javaClass.simpleName.contains("ClVector") &&
            !pattern.javaClass.simpleName.contains("ClMap")) {
            pattern = pattern.parent
        }
        val destructuring = pattern ?: return null
        val bindingVector = destructuring.parent?.takeIf { it.javaClass.simpleName.contains("ClVector") } ?: return null
        val form = bindingVector.parent as? ClList ?: return null
        val head = getHead(form) as? ClEditorSymbol ?: return null
        if (!isLetLikeBindingHead(head.text)) return null
        val pairs = bindingVector.children.filter {
            it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("[", "]")
        }
        val i = pairs.indexOf(destructuring)
        return if (i >= 0) pairs.getOrNull(i + 1) else null
    }

    private fun resolveCallbackParameter(
        definition: PsiElement,
        index: JsSymbolIndex,
        state: State,
    ): TypeResolution? {
        val params = definition.parent?.takeIf { it.javaClass.simpleName.contains("ClVector") } ?: return null
        val fn = params.parent as? ClList ?: return null
        val fnChildren = meaningfulChildren(fn)
        if ((fnChildren.firstOrNull() as? ClEditorSymbol)?.text !in setOf("fn", "fn*")) return null
        val parameterIndex = params.children.filter {
            it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("[", "]", "&")
        }.indexOf(definition)
        if (parameterIndex < 0) return null
        val call = fn.parent as? ClList ?: return null
        val callChildren = meaningfulChildren(call)
        val callHead = callChildren.firstOrNull() as? ClEditorSymbol ?: return null
        val method = callHead.text.removePrefix(".")
        if (!callHead.text.startsWith(".") || method !in setOf("then", "map", "filter", "forEach")) return null
        val receiver = callChildren.getOrNull(1) ?: return null
        val receiverType = resolve(receiver, index, state) ?: return null
        val named = receiverType.ref.primaryNamed() ?: return null
        val element = named.args.firstOrNull() ?: return null
        return TypeResolution(element, receiverType.confident, InferenceProvenance.CALL_SIGNATURE)
    }

    private fun callbackReturnType(callback: ClList, index: JsSymbolIndex, state: State): TypeResolution? {
        val children = meaningfulChildren(callback)
        val head = children.firstOrNull() as? ClEditorSymbol ?: return null
        if (head.text !in setOf("fn", "fn*")) return null
        return children.lastOrNull()?.let { resolve(it, index, state) }
    }

    private fun isLetLikeBindingHead(head: String): Boolean =
        when (head) {
            "let", "let*", "binding", "if-let", "if-let*", "when-let", "when-let*",
            "when-some", "if-some", "loop", "loop*", "doseq" -> true
            else -> false
        }

    private fun meaningfulChildren(list: ClList): List<PsiElement> =
        list.children.filter { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")" }

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

    private val DEFN_HEADS = setOf("defn", "defn-")

    /** Forms whose value is the last body expression. Binding forms are excluded — their body
     *  tail is still the last child, so they are safe to include where listed. */
    private val BODY_TAIL_HEADS = setOf("do", "let", "let*", "when", "when-let", "when-some", "if-let", "if-some", "binding")
}
