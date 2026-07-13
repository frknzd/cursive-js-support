package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.index.JsTypeRef
import com.cursivejssupport.npm.NpmBindingKind
import com.cursivejssupport.semantic.InteropSemanticService
import com.cursivejssupport.types.JsCallSignature
import com.cursivejssupport.types.JsMemberDescriptor
import com.intellij.openapi.components.service
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiWhiteSpace
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
    val effectiveSemanticMembers: List<JsMemberDescriptor>
        get() = semanticMembers.ifEmpty { structuralMembers(ref) }
}

private fun structuralMembers(ref: JsTypeRef): List<JsMemberDescriptor> = (ref as? JsTypeRef.Record)?.properties
    ?.map { (name, type) ->
        val projected = if (name in ref.optional) JsTypeRef.Union(listOf(type, JsTypeRef.Named("undefined"))) else type
        if (name in ref.methods) JsMemberDescriptor(name = name, kind = "method", returns = projected.display())
        else JsMemberDescriptor(name = name, kind = "property", type = projected.display())
    }.orEmpty()

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
        val parameterBindings: MutableMap<PsiElement, TypeResolution> = IdentityHashMap()
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
        CljsPsiTypeRules.literalType(element)?.let { return it }
        // 1. Constructor call (Fuse. ...) -> instance type
        if (element is ClList) {
            val head = getHead(element)
            if (head is ClEditorSymbol) {
                val ht = head.text ?: ""
                if (ht.endsWith(".") && ht.length > 1 && !ht.startsWith(".")) {
                    val constructorName = ht.removeSuffix(".")
                    val arguments = meaningfulChildren(element).drop(1).map { resolve(it, index, state)?.ref }
                    val binding = head.containingFile?.let { CljsPsiTypeRules.npmBinding(it, constructorName) }
                    if (binding != null && binding.kind in setOf(NpmBindingKind.REFER, NpmBindingKind.DEFAULT)) {
                        val export = binding.exportName ?: "default"
                        val descriptor = head.project.service<InteropSemanticService>()
                            .exportType(head.containingFile, binding.packageName, export)
                        JsTypeFlow.inferCall(descriptor?.constructSignatures.orEmpty(), arguments)?.let {
                            return TypeResolution(it.type, descriptor?.confidence?.let { confidence -> confidence >= 0.9 } == true)
                        }
                        val indexedType = index.resolveNpmExportType(binding.packageName, export)?.let(JsTypeRef::parse)
                        val indexedSignatures = indexedType?.let(index::resolveConstructSignatures).orEmpty()
                            .ifEmpty { indexedType?.let { index.resolveMembersOf(it)["new"]?.overloads }.orEmpty() }
                        JsTypeFlow.inferIndexedCall(indexedSignatures, arguments)?.let {
                            return TypeResolution(it.type, true, InferenceProvenance.CALL_SIGNATURE)
                        }
                    }
                    val staticType = index.resolveGlobalType(constructorName)?.let(JsTypeRef::parse)
                    val signatures = staticType?.let(index::resolveConstructSignatures).orEmpty()
                        .ifEmpty { staticType?.let { index.resolveMembersOf(it)["new"]?.overloads }.orEmpty() }
                    JsTypeFlow.inferIndexedCall(signatures, arguments)?.let { return TypeResolution(it.type, true) }
                    return named(constructorName)
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
            val binding = CljsPsiTypeRules.npmBinding(file, full)
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
        val hint = CljsPsiTypeRules.findTypeHint(element)
        if (hint != null) CljsPsiTypeRules.sanitizeType(hint)?.let { return named(it, provenance = InferenceProvenance.TYPE_HINT) }

        // 6. Resolve to definition and check its type
        val definition = resolveDefinition(element)
        if (definition != null && definition !== element) {
            state.parameterBindings[definition]?.let {
                return CljsPsiTypeRules.narrow(element, it.copy(provenance = InferenceProvenance.CALL_SIGNATURE), index)
            }
            if (!state.visitedDefinitions.add(definition)) return null
            try {
                // Check hint on definition (e.g., function param [^js/Type el])
                val defHint = CljsPsiTypeRules.findTypeHint(definition)
                if (defHint != null) CljsPsiTypeRules.sanitizeType(defHint)?.let { return named(it, provenance = InferenceProvenance.TYPE_HINT) }

                // If it's a let-binding, try to resolve the type of its initializer
                val initializer = findBindingInitializer(definition)
                if (initializer != null) {
                    return resolve(initializer, index, state)?.copy(provenance = InferenceProvenance.LEXICAL_BINDING)
                        ?.let { CljsPsiTypeRules.narrow(element, it, index) }
                }

                findDestructuredBindingInitializer(definition)?.let { destructured ->
                    val container = resolve(destructured, index, state) ?: return@let
                    val elementType = CljsPsiTypeRules.destructuredBindingType(definition, container, index)
                    if (elementType != null) {
                        return CljsPsiTypeRules.narrow(element,
                            TypeResolution(elementType, container.confident, InferenceProvenance.LEXICAL_BINDING), index)
                    }
                }

                CljsCallbackInference.resolveParameter(
                    definition, index,
                    resolve = { resolve(it, index, state) },
                    threadedInput = { threadedInputBefore(it, index, state) },
                )?.let { return CljsPsiTypeRules.narrow(element, it, index) }

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
        if (head is ClEditorSymbol) {
            CljsCoreTypeRules.resolve(
                element, head.text, index,
                resolve = { resolve(it, index, state) },
                callbackReturn = { callback, arguments -> callbackReturnType(callback, index, state, arguments) },
                union = ::union,
            )?.let { return it }
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
            receiverType.effectiveSemanticMembers.firstOrNull { it.name == memberName }?.let { member ->
                val value = if (member.kind == "method") member.returns else member.type
                return TypeResolution(JsTypeRef.parse(value), receiverType.confident, InferenceProvenance.CALL_SIGNATURE)
            }

            val callback = meaningfulChildren(element).getOrNull(2)
            if (callback != null && memberName in setOf("then", "catch", "map", "flatMap")) {
                val input = if (memberName in setOf("then", "catch")) receiverType.ref.primaryNamed()?.args?.firstOrNull()
                else collectionElementType(receiverType.ref)
                callbackReturnType(callback, index, state,
                    listOfNotNull(input?.let { TypeResolution(it, receiverType.confident) }))?.let { callbackResult ->
                    val container = when (memberName) {
                        "then", "catch" -> "Promise"
                        else -> "Array"
                    }
                    val result = when (memberName) {
                        "then" -> JsTypeFlow.awaited(callbackResult.ref)
                        "catch" -> JsTypeFlow.union(listOfNotNull(
                            receiverType.ref.primaryNamed()?.args?.firstOrNull(),
                            JsTypeFlow.awaited(callbackResult.ref),
                        ))
                        "flatMap" -> collectionElementType(callbackResult.ref) ?: callbackResult.ref
                        else -> callbackResult.ref
                    }
                    return TypeResolution(
                        JsTypeRef.Named(container, listOf(result)),
                        receiverType.confident && callbackResult.confident,
                        InferenceProvenance.CALL_SIGNATURE,
                    )
                }
            }

            if (memberName == "finally") return receiverType
            val resolvedMember = index.resolveMembersOf(receiverType.ref)[memberName] ?: return null
            val substitution = index.substitutionFor(receiverType.ref)
            val projectedSignatures = resolvedMember.overloads.filter { it.kind == "method" }.map { signature ->
                signature.copy(
                    params = signature.params.map { param ->
                        param.copy(type = index.substitute(JsTypeRef.parse(param.type), substitution).display())
                    },
                    returns = index.substitute(JsTypeRef.parse(signature.returns), substitution).display(),
                )
            }
            if (projectedSignatures.isNotEmpty()) {
                val arguments = meaningfulChildren(element).drop(2).map { resolve(it, index, state)?.ref }
                JsTypeFlow.inferIndexedCall(projectedSignatures, arguments)?.let {
                    return TypeResolution(it.type, receiverType.confident, InferenceProvenance.CALL_SIGNATURE)
                }
            }
            val member = resolvedMember.first ?: return null
            val projected = index.substitute(index.memberValueType(member), substitution)
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
            val binding = head.containingFile?.let { CljsPsiTypeRules.npmBinding(it, head.text) }
            if (binding != null && binding.kind in setOf(NpmBindingKind.REFER, NpmBindingKind.DEFAULT)) {
                val export = binding.exportName ?: "default"
                val semantic = head.project.service<InteropSemanticService>()
                    .exportType(head.containingFile, binding.packageName, export)
                val arguments = meaningfulChildren(element).drop(1).map { resolve(it, index, state)?.ref }
                JsTypeFlow.inferCall(semantic?.callSignatures.orEmpty(), arguments)?.let { inferred ->
                    return TypeResolution(
                        inferred.type,
                        semantic?.confidence?.let { it >= 0.9 } == true,
                        InferenceProvenance.CALL_SIGNATURE,
                        inferred.returnMembers,
                    )
                }
                val returns = index.resolveNpmExportMembers(binding.packageName, export).orEmpty()
                JsTypeFlow.inferIndexedCall(returns, arguments)?.let { inferred ->
                    return TypeResolution(
                        inferred.type,
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
                        val arguments = meaningfulChildren(element).drop(1).map { resolve(it, index, state) }
                        defnReturnType(definition, index, state, arguments)?.let { return it }
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
    private fun defnReturnType(
        definition: PsiElement,
        index: JsSymbolIndex,
        state: State,
        arguments: List<TypeResolution?> = emptyList(),
    ): TypeResolution? {
        val defnList = definition.parent as? ClList ?: return null
        val children = meaningfulChildren(defnList)
        val headText = (children.firstOrNull() as? ClEditorSymbol)?.text ?: return null
        if (headText !in DEFN_HEADS) return null
        if (children.getOrNull(1) !== definition &&
            JsInteropPsi.enclosingEditorSymbol(children.getOrNull(1) ?: return null) !== definition
        ) return null

        CljsPsiTypeRules.findTypeHint(definition)?.let { hint ->
            CljsPsiTypeRules.sanitizeType(hint)?.let { return named(it) }
        }

        // Single arity: (defn name [params] body…) — body is everything after the param vector.
        // Multi-arity: union the body tail of every arity.
        val afterName = children.drop(2).filter { it !is PsiComment }
        val paramVectorIdx = afterName.indexOfFirst { it.javaClass.simpleName.contains("ClVector") }
        if (paramVectorIdx >= 0) {
            val body = afterName.drop(paramVectorIdx + 1).lastOrNull() ?: return null
            return withParameterBindings(afterName[paramVectorIdx], arguments, state) { resolve(body, index, state) }
        }
        val arities = afterName.filterIsInstance<ClList>()
        val matchingArities = arities.filter { arity ->
            val params = meaningfulChildren(arity).firstOrNull { it.javaClass.simpleName.contains("ClVector") }
                ?: return@filter false
            val entries = parameterElements(params)
            val rest = entries.indexOfFirst { it.text == "&" }
            if (rest >= 0) arguments.size >= rest else arguments.size == entries.size
        }.ifEmpty { arities }
        val returns = matchingArities.mapNotNull { arity ->
            val parts = meaningfulChildren(arity)
            val params = parts.firstOrNull { it.javaClass.simpleName.contains("ClVector") } ?: return@mapNotNull null
            withParameterBindings(params, arguments, state) {
                parts.lastOrNull()?.let { resolve(it, index, state) }
            }
        }
        return union(returns)
    }

    private fun withParameterBindings(
        vector: PsiElement,
        arguments: List<TypeResolution?>,
        state: State,
        block: () -> TypeResolution?,
    ): TypeResolution? {
        val entries = parameterElements(vector)
        val restMarker = entries.indexOfFirst { it.text == "&" }
        val parameters = entries.filter { it.text != "&" }
        val previous = parameters.associateWith { state.parameterBindings[it] }
        val fixedCount = if (restMarker >= 0) restMarker else parameters.size
        parameters.take(fixedCount).zip(arguments).forEach { (parameter, argument) ->
            if (argument != null) state.parameterBindings[parameter] = argument
        }
        if (restMarker >= 0) {
            val rest = entries.getOrNull(restMarker + 1)
            val types = arguments.drop(fixedCount).mapNotNull { it?.ref }
            if (rest != null && types.isNotEmpty()) {
                state.parameterBindings[rest] = TypeResolution(
                    JsTypeRef.Named("Array", listOf(JsTypeFlow.union(types))), true, InferenceProvenance.CALL_SIGNATURE)
            }
        }
        return try {
            block()
        } finally {
            previous.forEach { (parameter, value) ->
                if (value == null) state.parameterBindings.remove(parameter) else state.parameterBindings[parameter] = value
            }
        }
    }

    private fun parameterElements(vector: PsiElement): List<PsiElement> = vector.children.filter {
        it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("[", "]")
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
        var destructuring: PsiElement? = definition.parent
        var bindingVector: PsiElement? = null
        while (destructuring != null) {
            val isPattern = destructuring.javaClass.simpleName.let { it.contains("ClVector") || it.contains("ClMap") }
            val possibleBinding = destructuring.parent?.takeIf { it.javaClass.simpleName.contains("ClVector") }
            val form = possibleBinding?.parent as? ClList
            val head = form?.let(::getHead) as? ClEditorSymbol
            if (isPattern && possibleBinding != null && head?.text?.let(::isLetLikeBindingHead) == true) {
                bindingVector = possibleBinding
                break
            }
            destructuring = destructuring.parent
        }
        val pattern = destructuring ?: return null
        val binding = bindingVector ?: return null
        val pairs = binding.children.filter {
            it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("[", "]")
        }
        val i = pairs.indexOf(pattern)
        return if (i >= 0) pairs.getOrNull(i + 1) else null
    }

    private fun threadedInputBefore(call: ClList, index: JsSymbolIndex, state: State): TypeResolution? {
        val thread = call.parent as? ClList ?: return null
        val children = meaningfulChildren(thread)
        val head = (children.firstOrNull() as? ClEditorSymbol)?.text
        if (head in setOf("->>", "some->>")) {
            val callIndex = children.indexOfFirst { it === call }
            if (callIndex < 2) return null
            val current = resolve(children[1], index, state) ?: return null
            return applySequenceSteps(current, children.subList(2, callIndex), index, state)
        }
        val comp = thread.takeIf { head == "comp" }
        val owner = (comp?.parent as? ClList) ?: thread
        val ownerChildren = meaningfulChildren(owner)
        val ownerHead = (ownerChildren.firstOrNull() as? ClEditorSymbol)?.text
        if (ownerHead !in setOf("into", "sequence", "eduction", "transduce")) return null
        val source = ownerChildren.lastOrNull()?.let { resolve(it, index, state) } ?: return null
        val current = CljsCoreTypeRules.sequenceView(source) ?: return null
        if (comp == null) return current
        val compChildren = meaningfulChildren(comp)
        val callIndex = compChildren.indexOfFirst { it === call }
        return applySequenceSteps(current, compChildren.subList(1, callIndex.coerceAtLeast(1)), index, state)
    }

    private fun applySequenceSteps(
        initial: TypeResolution,
        steps: List<PsiElement>,
        index: JsSymbolIndex,
        state: State,
    ): TypeResolution? {
        var current = initial
        for (step in steps) {
            val stepHead = when (step) {
                is ClEditorSymbol -> step.text
                is ClList -> (meaningfulChildren(step).firstOrNull() as? ClEditorSymbol)?.text
                else -> null
            }
            current = when (stepHead) {
                in CljsCoreTypeRules.adapterHeads -> CljsCoreTypeRules.sequenceView(current) ?: return null
                in CljsCoreTypeRules.preservingHeads -> current
                "into" -> current
                "partition", "partition-all" -> {
                    val item = collectionElementType(current.ref) ?: return null
                    TypeResolution(JsTypeRef.Named("Array", listOf(JsTypeRef.Named("Array", listOf(item)))),
                        current.confident, InferenceProvenance.CALL_SIGNATURE)
                }
                "flatten" -> {
                    var item = collectionElementType(current.ref) ?: return null
                    while (true) item = collectionElementType(item) ?: break
                    TypeResolution(JsTypeRef.Named("Array", listOf(item)), current.confident,
                        InferenceProvenance.CALL_SIGNATURE)
                }
                in CljsCoreTypeRules.mappingHeads -> {
                    val callback = (step as? ClList)?.let { meaningfulChildren(it).getOrNull(1) }
                        ?: return null
                    val input = collectionElementType(current.ref)?.let { TypeResolution(it, current.confident) }
                    val callbackArguments = if (stepHead in setOf("map-indexed", "keep-indexed")) {
                        listOf(TypeResolution(JsTypeRef.Named("number"), true)) + listOfNotNull(input)
                    } else listOfNotNull(input)
                    val result = callbackReturnType(callback, index, state, callbackArguments) ?: return null
                    val element = when (stepHead) {
                        "keep", "keep-indexed" -> JsTypeFlow.removeNullish(result.ref)
                        "mapcat" -> collectionElementType(result.ref) ?: result.ref
                        else -> result.ref
                    }
                    TypeResolution(JsTypeRef.Named("Array", listOf(element)), result.confident,
                        InferenceProvenance.CALL_SIGNATURE)
                }
                else -> return null
            }
        }
        return current
    }

    private fun callbackReturnType(
        callback: PsiElement,
        index: JsSymbolIndex,
        state: State,
        arguments: List<TypeResolution?> = emptyList(),
    ): TypeResolution? {
        if (callback is ClEditorSymbol) {
            val definition = resolveDefinition(callback) ?: return null
            return defnReturnType(definition, index, state, arguments)
        }
        val list = callback as? ClList ?: return null
        val children = meaningfulChildren(list)
        val head = children.firstOrNull() as? ClEditorSymbol ?: return null
        if (head.text !in setOf("fn", "fn*")) return null
        val params = children.firstOrNull { it.javaClass.simpleName.contains("ClVector") }
        return if (params != null) withParameterBindings(params, arguments, state) {
            children.lastOrNull()?.let { resolve(it, index, state) }
        } else children.lastOrNull()?.let { resolve(it, index, state) }
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

    private val DEFN_HEADS = setOf("defn", "defn-")
    /** Forms whose value is the last body expression. Binding forms are excluded — their body
     *  tail is still the last child, so they are safe to include where listed. */
    private val BODY_TAIL_HEADS = setOf(
        "do", "let", "let*", "when", "when-not", "when-let", "when-some", "if-let", "if-some", "binding",
    )
}

internal fun collectionElementType(ref: JsTypeRef): JsTypeRef? = when (ref) {
    is JsTypeRef.Named -> when (ref.name) {
        "Array", "ReadonlyArray", "Iterable", "IterableIterator", "Iterator", "Generator", "AsyncGenerator",
        "AsyncIterable", "ArrayLike", "IArrayLike", "Set", "ReadonlySet", "WeakSet", "NodeListOf",
        "HTMLCollectionOf" -> ref.args.firstOrNull()
        "Map", "ReadonlyMap", "WeakMap" -> ref.args.takeIf { it.size >= 2 }?.let { JsTypeRef.Named("Tuple", it.take(2)) }
        "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array", "Uint16Array", "Int32Array",
        "Uint32Array", "Float32Array", "Float64Array", "BigInt64Array", "BigUint64Array" -> JsTypeRef.Named("number")
        else -> null
    }
    is JsTypeRef.Union -> {
        val elements = ref.members.mapNotNull(::collectionElementType).distinct()
        when (elements.size) {
            0 -> null
            1 -> elements.first()
            else -> JsTypeRef.Union(elements)
        }
    }
    is JsTypeRef.Record -> null
    JsTypeRef.Unknown -> null
}
