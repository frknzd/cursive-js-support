package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.index.JsTypeRef
import com.cursivejssupport.npm.NpmBindingKind
import com.cursivejssupport.semantic.InteropSemanticService
import com.intellij.openapi.components.service
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol

internal object CljsCallbackInference {
    fun resolveParameter(
        definition: PsiElement,
        index: JsSymbolIndex,
        resolve: (PsiElement?) -> TypeResolution?,
        threadedInput: (ClList) -> TypeResolution?,
    ): TypeResolution? {
        val params = definition.parent?.takeIf { it.javaClass.simpleName.contains("ClVector") } ?: return null
        val fn = params.parent as? ClList ?: return null
        if ((meaningfulChildren(fn).firstOrNull() as? ClEditorSymbol)?.text !in setOf("fn", "fn*")) return null
        val parameterIndex = parameterElements(params).indexOf(definition)
        if (parameterIndex < 0) return null
        val call = fn.parent as? ClList ?: return null
        val children = meaningfulChildren(call)
        val head = children.firstOrNull() as? ClEditorSymbol ?: return null
        coreParameter(head.text, parameterIndex, call, children, resolve, threadedInput)?.let { return it }
        signatureParameter(head, fn, parameterIndex, children, index, resolve)?.let { return it }
        return null
    }

    private fun coreParameter(
        head: String,
        parameterIndex: Int,
        call: ClList,
        children: List<PsiElement>,
        resolve: (PsiElement?) -> TypeResolution?,
        threadedInput: (ClList) -> TypeResolution?,
    ): TypeResolution? {
        if (head in setOf("reduce", "reduce-kv")) {
            val collection = resolve(children.lastOrNull()) ?: return null
            val element = collectionElementType(collection.ref) ?: return null
            val initial = children.takeIf { it.size > 3 }?.getOrNull(2)?.let(resolve)?.ref
            val type = if (parameterIndex == 0) initial ?: element else element.takeIf { parameterIndex == 1 }
            return type?.let { TypeResolution(it, collection.confident, InferenceProvenance.CALL_SIGNATURE) }
        }
        val method = head.removePrefix(".")
        if (head.startsWith(".") && method in setOf("then", "map", "filter", "forEach")) {
            val receiver = resolve(children.getOrNull(1)) ?: return null
            val type = when {
                method == "then" && parameterIndex == 0 -> receiver.ref.primaryNamed()?.args?.firstOrNull()
                method != "then" && parameterIndex == 0 -> collectionElementType(receiver.ref)
                method != "then" && parameterIndex == 1 -> JsTypeRef.Named("number")
                method != "then" && parameterIndex == 2 -> receiver.ref
                else -> null
            }
            if (type != null) return TypeResolution(type, receiver.confident, InferenceProvenance.CALL_SIGNATURE)
        }
        if (head !in CORE_HEADS) return null
        if (head in INDEXED_HEADS && parameterIndex == 0) {
            return TypeResolution(JsTypeRef.Named("number"), true, InferenceProvenance.CALL_SIGNATURE)
        }
        val collectionIndex = if (head in MULTI_COLLECTION_HEADS) 2 + parameterIndex else 2
        val collection = resolve(children.getOrNull(collectionIndex)) ?: threadedInput(call) ?: return null
        val element = collectionElementType(collection.ref) ?: return null
        return TypeResolution(element, collection.confident, InferenceProvenance.CALL_SIGNATURE)
    }

    private fun signatureParameter(
        head: ClEditorSymbol,
        fn: ClList,
        parameterIndex: Int,
        children: List<PsiElement>,
        index: JsSymbolIndex,
        resolve: (PsiElement?) -> TypeResolution?,
    ): TypeResolution? {
        val rawTypes: List<String>
        val confident: Boolean
        when {
            head.text.startsWith(".") -> {
                val callbackArgument = children.indexOfFirst { it === fn } - 2
                val receiver = resolve(children.getOrNull(1)) ?: return null
                val method = head.text.removePrefix(".")
                val substitution = index.substitutionFor(receiver.ref)
                rawTypes = receiver.effectiveSemanticMembers.filter { it.name == method }
                    .mapNotNull { it.params.getOrNull(callbackArgument)?.type } +
                    index.resolveMembersOf(receiver.ref)[method]?.overloads.orEmpty()
                        .mapNotNull { it.params.getOrNull(callbackArgument)?.type }
                        .map { index.substitute(JsTypeRef.parse(it), substitution).display() }
                confident = receiver.confident
            }
            head.text.startsWith("js/") -> {
                val callbackArgument = children.indexOfFirst { it === fn } - 1
                val segments = JsInteropChain.segmentsFromFullText(head.text).orEmpty()
                val receiver = segments.takeIf { it.size > 1 }?.dropLast(1)?.let(index::resolveJsChainTypeRef)
                rawTypes = receiver?.let { index.resolveMembersOf(it)[segments.last()]?.overloads }.orEmpty()
                    .mapNotNull { it.params.getOrNull(callbackArgument)?.type }
                confident = true
            }
            else -> return npmParameter(head, fn, parameterIndex, children, index, resolve)
        }
        return inferredParameter(rawTypes, parameterIndex, index)?.let {
            TypeResolution(it, confident, InferenceProvenance.CALL_SIGNATURE)
        }
    }

    private fun npmParameter(
        head: ClEditorSymbol,
        fn: ClList,
        parameterIndex: Int,
        children: List<PsiElement>,
        index: JsSymbolIndex,
        resolve: (PsiElement?) -> TypeResolution?,
    ): TypeResolution? {
        val callbackArgument = children.indexOfFirst { it === fn } - 1
        val binding = head.containingFile?.let { CljsPsiTypeRules.npmBinding(it, head.text) }
            ?.takeIf { it.kind in setOf(NpmBindingKind.REFER, NpmBindingKind.DEFAULT, NpmBindingKind.RELATIVE) } ?: return null
        val descriptor = head.project.service<InteropSemanticService>()
            .exportType(head.containingFile, binding.packageName, binding.exportName ?: "default")
        val arguments = children.drop(1).map { argument ->
            if (argument === fn) null else resolve(argument)?.ref
        }
        val semanticTypes = descriptor?.callSignatures.orEmpty().mapNotNull {
            JsTypeFlow.instantiateParams(it, arguments).getOrNull(callbackArgument)?.type
        }
        val indexedTypes = index.resolveNpmExportMembers(binding.packageName, binding.exportName ?: "default").orEmpty()
            .mapNotNull { JsTypeFlow.instantiateParams(it, arguments).getOrNull(callbackArgument)?.type }
        val rawTypes = semanticTypes + indexedTypes
        return inferredParameter(rawTypes, parameterIndex, index)?.let {
            TypeResolution(it, descriptor?.confidence?.let { confidence -> confidence >= 0.9 } == true,
                InferenceProvenance.CALL_SIGNATURE)
        }
    }

    private fun inferredParameter(rawTypes: List<String>, parameterIndex: Int, index: JsSymbolIndex): JsTypeRef? {
        val inferred = rawTypes.flatMap { raw ->
            JsTypeFlow.callbackParameters(JsTypeRef.parse(raw), index::resolveCallSignatures)
                .ifEmpty { JsTypeFlow.callbackParameters(raw) }
                .getOrNull(parameterIndex)?.let(::listOf).orEmpty()
        }.distinct()
        return inferred.takeIf(List<*>::isNotEmpty)?.let(JsTypeFlow::union)
    }

    private fun parameterElements(vector: PsiElement): List<PsiElement> = vector.children.filter {
        it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("[", "]", "&")
    }

    private fun meaningfulChildren(list: ClList): List<PsiElement> = list.children.filter {
        it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("(", ")")
    }

    private val CORE_HEADS = setOf(
        "map", "mapv", "map-indexed", "filter", "remove", "keep", "keep-indexed", "some", "every?",
    )
    private val INDEXED_HEADS = setOf("map-indexed", "keep-indexed")
    private val MULTI_COLLECTION_HEADS = setOf("map", "mapv")
}
