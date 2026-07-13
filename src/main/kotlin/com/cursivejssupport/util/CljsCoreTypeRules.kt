package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.index.JsTypeRef
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import cursive.psi.api.ClList

internal object CljsCoreTypeRules {
    val mappingHeads = setOf("map", "mapv", "map-indexed", "keep", "keep-indexed", "mapcat")
    val preservingHeads = setOf(
        "filter", "remove", "take", "drop", "take-while", "drop-while", "take-nth", "distinct", "dedupe",
        "reverse", "sort", "sort-by", "shuffle",
    )
    val adapterHeads = setOf(
        "array-seq", "cljs.core/array-seq", "seq", "cljs.core/seq", "vec", "cljs.core/vec",
        "into-array", "cljs.core/into-array", "to-array", "cljs.core/to-array",
    )

    fun resolve(
        list: ClList,
        head: String,
        index: JsSymbolIndex,
        resolve: (PsiElement?) -> TypeResolution?,
        callbackReturn: (PsiElement, List<TypeResolution?>) -> TypeResolution?,
        union: (List<TypeResolution>) -> TypeResolution?,
    ): TypeResolution? {
        val children = meaningfulChildren(list)
        resolveMapping(head, children, resolve, callbackReturn)?.let { return it }
        if (head in preservingHeads) return children.lastOrNull()?.let(resolve)?.let(::sequenceView)
        when (head) {
            "concat" -> return arrayOfUnion(children.drop(1).mapNotNull { resolve(it)?.ref?.let(::collectionElementType) })
            "into" -> return resolveInto(children, resolve, callbackReturn)
            "sequence", "eduction" -> return resolveTransducedSequence(children, resolve, callbackReturn)
            "partition", "partition-all" -> return children.lastOrNull()?.let(resolve)?.let(::partition)
            "flatten" -> return children.getOrNull(1)?.let(resolve)?.let(::flatten)
            "reduce", "reduce-kv" -> return resolveReduce(children, resolve, callbackReturn)
            "transduce" -> return resolveTransduce(children, resolve, callbackReturn)
        }
        resolveStaticCall(head, children, index, resolve)?.let { return it }
        when (head) {
            "set!" -> return resolve(children.getOrNull(2))
            "if", "if-not", "if-let", "if-some" -> return union(children.drop(2).mapNotNull(resolve))
            "cond", "condp", "case" -> return resolveConditional(head, children, resolve, union)
            "or" -> return union(children.drop(1).mapNotNull(resolve))
            "and" -> return resolve(children.lastOrNull())
            "first", "second", "last", "nth" -> return elementAt(children, resolve)
            "aget", "get", "goog.object/get", "js/goog.object.get" -> return keyedAccess(children, index, resolve)
        }
        if (head in adapterHeads) return resolve(children.getOrNull(1))?.let(::sequenceView)
        return null
    }

    fun sequenceView(resolved: TypeResolution): TypeResolution? = collectionElementType(resolved.ref)?.let {
        TypeResolution(JsTypeRef.Named("Array", listOf(it)), resolved.confident, InferenceProvenance.CALL_SIGNATURE)
    }

    fun staticCollectionCall(segments: List<String>, arguments: List<JsTypeRef?>): JsTypeRef? {
        val name = segments.joinToString(".")
        val first = arguments.firstOrNull() ?: return null
        val element = collectionElementType(first)
        return when (name) {
            "Promise.all" -> element?.let { promiseOf(JsTypeRef.Named("Array", listOf(JsTypeFlow.awaited(it)))) }
            "Promise.race", "Promise.any" -> element?.let { promiseOf(JsTypeFlow.awaited(it)) }
            "Promise.allSettled" -> element?.let {
                promiseOf(JsTypeRef.Named("Array", listOf(JsTypeRef.Named("PromiseSettledResult", listOf(JsTypeFlow.awaited(it))))))
            }
            "Array.from", "Array.of" -> element?.let { JsTypeRef.Named("Array", listOf(it)) }
                ?: JsTypeRef.Named("Array", listOf(JsTypeFlow.union(arguments.filterNotNull())))
            else -> null
        }
    }

    private fun resolveMapping(
        head: String,
        children: List<PsiElement>,
        resolve: (PsiElement?) -> TypeResolution?,
        callbackReturn: (PsiElement, List<TypeResolution?>) -> TypeResolution?,
    ): TypeResolution? {
        if (head !in mappingHeads) return null
        val callback = children.getOrNull(1) ?: return null
        val sourceElements = children.drop(2).mapNotNull { source ->
            resolve(source)?.ref?.let(::collectionElementType)?.let { TypeResolution(it, true) }
        }
        val arguments = if (head in setOf("map-indexed", "keep-indexed")) {
            listOf(TypeResolution(JsTypeRef.Named("number"), true)) + sourceElements.take(1)
        } else sourceElements
        val result = callbackReturn(callback, arguments) ?: return null
        val element = when (head) {
            "keep", "keep-indexed" -> JsTypeFlow.removeNullish(result.ref)
            "mapcat" -> collectionElementType(result.ref) ?: result.ref
            else -> result.ref
        }
        return TypeResolution(JsTypeRef.Named("Array", listOf(element)), result.confident,
            InferenceProvenance.CALL_SIGNATURE)
    }

    private fun resolveReduce(
        children: List<PsiElement>,
        resolve: (PsiElement?) -> TypeResolution?,
        callbackReturn: (PsiElement, List<TypeResolution?>) -> TypeResolution?,
    ): TypeResolution? {
        val callback = children.getOrNull(1) ?: return null
        val collection = resolve(children.lastOrNull())
        val element = collection?.ref?.let(::collectionElementType)
        val initial = children.takeIf { it.size > 3 }?.getOrNull(2)?.let(resolve)
        val args = listOfNotNull(
            initial ?: element?.let { TypeResolution(it, collection?.confident == true) },
            element?.let { TypeResolution(it, collection?.confident == true) },
        )
        return callbackReturn(callback, args) ?: initial
    }

    private fun resolveInto(
        children: List<PsiElement>,
        resolve: (PsiElement?) -> TypeResolution?,
        callbackReturn: (PsiElement, List<TypeResolution?>) -> TypeResolution?,
    ): TypeResolution? {
        val source = children.lastOrNull()?.let(resolve) ?: return null
        if (children.size < 4) return sequenceView(source)
        val input = collectionElementType(source.ref) ?: return sequenceView(source)
        val output = transducedElement(children[2], input, source.confident, callbackReturn) ?: input
        return TypeResolution(JsTypeRef.Named("Array", listOf(output)), source.confident,
            InferenceProvenance.CALL_SIGNATURE)
    }

    private fun resolveTransducedSequence(
        children: List<PsiElement>,
        resolve: (PsiElement?) -> TypeResolution?,
        callbackReturn: (PsiElement, List<TypeResolution?>) -> TypeResolution?,
    ): TypeResolution? {
        val source = children.lastOrNull()?.let(resolve) ?: return null
        val input = collectionElementType(source.ref) ?: return null
        val output = children.getOrNull(1)?.let { transducedElement(it, input, source.confident, callbackReturn) } ?: input
        return TypeResolution(JsTypeRef.Named("Array", listOf(output)), source.confident,
            InferenceProvenance.CALL_SIGNATURE)
    }

    private fun resolveTransduce(
        children: List<PsiElement>,
        resolve: (PsiElement?) -> TypeResolution?,
        callbackReturn: (PsiElement, List<TypeResolution?>) -> TypeResolution?,
    ): TypeResolution? {
        val source = children.lastOrNull()?.let(resolve) ?: return null
        val input = collectionElementType(source.ref) ?: return null
        val transformed = children.getOrNull(1)?.let { transducedElement(it, input, source.confident, callbackReturn) } ?: input
        val initial = children.takeIf { it.size > 4 }?.getOrNull(3)?.let(resolve)
        val accumulator = initial ?: TypeResolution(transformed, source.confident)
        val reducer = children.getOrNull(2) ?: return accumulator
        return callbackReturn(reducer, listOf(accumulator, TypeResolution(transformed, source.confident))) ?: accumulator
    }

    private fun transducedElement(
        form: PsiElement,
        input: JsTypeRef,
        confident: Boolean,
        callbackReturn: (PsiElement, List<TypeResolution?>) -> TypeResolution?,
    ): JsTypeRef? {
        val list = form as? ClList ?: return input
        val children = meaningfulChildren(list)
        val head = children.firstOrNull()?.text
        if (head == "comp") {
            return children.drop(1).fold(input) { current, step ->
                transducedElement(step, current, confident, callbackReturn) ?: current
            }
        }
        if (head in preservingHeads) return input
        if (head == "cat") return collectionElementType(input) ?: input
        if (head !in mappingHeads) return input
        val callback = children.getOrNull(1) ?: return input
        val args = if (head in setOf("map-indexed", "keep-indexed")) {
            listOf(TypeResolution(JsTypeRef.Named("number"), true), TypeResolution(input, confident))
        } else listOf(TypeResolution(input, confident))
        val result = callbackReturn(callback, args)?.ref ?: return null
        return when (head) {
            "keep", "keep-indexed" -> JsTypeFlow.removeNullish(result)
            "mapcat" -> collectionElementType(result) ?: result
            else -> result
        }
    }

    private fun resolveStaticCall(
        head: String,
        children: List<PsiElement>,
        index: JsSymbolIndex,
        resolve: (PsiElement?) -> TypeResolution?,
    ): TypeResolution? {
        if (!head.startsWith("js/")) return null
        val segments = JsInteropChain.segmentsFromFullText(head).orEmpty()
        if (segments.size < 2) return null
        val arguments = children.drop(1).map { resolve(it)?.ref }
        staticCollectionCall(segments, arguments)?.let {
            return TypeResolution(it, arguments.none { argument -> argument == null }, InferenceProvenance.CALL_SIGNATURE)
        }
        val receiver = index.resolveJsChainTypeRef(segments.dropLast(1))
        val signatures = receiver?.let { index.resolveMembersOf(it)[segments.last()]?.overloads }.orEmpty()
        return JsTypeFlow.inferIndexedCall(signatures, arguments)?.let {
            TypeResolution(it.type, true, InferenceProvenance.CALL_SIGNATURE)
        }
    }

    private fun resolveConditional(
        head: String,
        children: List<PsiElement>,
        resolve: (PsiElement?) -> TypeResolution?,
        union: (List<TypeResolution>) -> TypeResolution?,
    ): TypeResolution? {
        val offset = when (head) { "cond" -> 1; "case" -> 2; else -> 3 }
        val clauses = children.drop(offset)
        val values = clauses.chunked(2).mapNotNull { it.getOrNull(1) }.toMutableList()
        if (clauses.size % 2 == 1) values += clauses.last()
        return union(values.mapNotNull(resolve))
    }

    private fun elementAt(children: List<PsiElement>, resolve: (PsiElement?) -> TypeResolution?): TypeResolution? {
        val source = resolve(children.getOrNull(1)) ?: return null
        val element = collectionElementType(source.ref) ?: return null
        return TypeResolution(element, source.confident, InferenceProvenance.CALL_SIGNATURE)
    }

    private fun keyedAccess(
        children: List<PsiElement>,
        index: JsSymbolIndex,
        resolve: (PsiElement?) -> TypeResolution?,
    ): TypeResolution? {
        val container = resolve(children.getOrNull(1)) ?: return null
        val key = literalKey(children.getOrNull(2))
        if (key != null) {
            container.effectiveSemanticMembers.firstOrNull { it.name == key }?.let {
                return TypeResolution(JsTypeRef.parse(it.type), container.confident, InferenceProvenance.CALL_SIGNATURE)
            }
            JsTypeFlow.keyedAccess(container.ref, key) { type, member ->
                index.resolveMembersOf(type)[member]?.first?.let(index::memberValueType)
                    ?: index.resolveIndexedValueType(type, member.toIntOrNull() != null)
            }?.let { return TypeResolution(it, container.confident, InferenceProvenance.CALL_SIGNATURE) }
        }
        return collectionElementType(container.ref)?.let {
            TypeResolution(it, container.confident, InferenceProvenance.CALL_SIGNATURE)
        }
    }

    private fun partition(source: TypeResolution): TypeResolution? = collectionElementType(source.ref)?.let {
        TypeResolution(JsTypeRef.Named("Array", listOf(JsTypeRef.Named("Array", listOf(it)))), source.confident,
            InferenceProvenance.CALL_SIGNATURE)
    }

    private fun flatten(source: TypeResolution): TypeResolution? {
        var item = collectionElementType(source.ref) ?: return null
        while (true) item = collectionElementType(item) ?: break
        return TypeResolution(JsTypeRef.Named("Array", listOf(item)), source.confident,
            InferenceProvenance.CALL_SIGNATURE)
    }

    private fun arrayOfUnion(elements: List<JsTypeRef>): TypeResolution? = elements.takeIf(List<*>::isNotEmpty)?.let {
        TypeResolution(JsTypeRef.Named("Array", listOf(JsTypeFlow.union(elements))), true,
            InferenceProvenance.CALL_SIGNATURE)
    }

    private fun literalKey(element: PsiElement?): String? {
        val text = element?.text?.trim() ?: return null
        return when {
            text.length >= 2 && text.first() == '"' && text.last() == '"' -> text.substring(1, text.length - 1)
            text.startsWith(":") -> text.substringAfterLast('/').removePrefix(":")
            text.matches(Regex("\\d+")) -> text
            else -> null
        }
    }

    private fun meaningfulChildren(list: ClList): List<PsiElement> = list.children.filter {
        it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("(", ")")
    }

    private fun promiseOf(type: JsTypeRef) = JsTypeRef.Named("Promise", listOf(type))
}
