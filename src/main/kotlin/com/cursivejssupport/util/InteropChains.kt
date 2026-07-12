package com.cursivejssupport.util

import com.cursivejssupport.index.JsSymbolIndex
import com.intellij.psi.PsiElement
import cursive.psi.api.ClList

/** A parsed chain-macro form: `(.. root step…)`, `(-> root step…)`, `(doto root step…)`, … */
data class ChainForm(
    val kind: ChainKind,
    val list: ClList,
    val root: PsiElement,
    val steps: List<ChainStep>,
)

/** One step element of a [ChainForm]. */
sealed interface ChainStep {
    val element: PsiElement

    /** A member-access step: bare `name` / `.name` / `.-name` or a list step `(.name args…)`. */
    data class Interop(
        override val element: PsiElement,
        val spec: ChainStepSpec,
        /** The element carrying the member name (== [element] for bare steps, the head for list steps). */
        val anchor: PsiElement,
        /** Arguments of a list step `(method a b)`; empty for bare steps. */
        val callArgs: List<PsiElement>,
    ) : ChainStep

    /** A step that is not a member access (e.g. `(str …)` in `->`); type flow stops here. */
    data class Opaque(override val element: PsiElement) : ChainStep
}

/** Receiver context for the chain step a symbol sits in. */
data class ChainStepContext(
    val form: ChainForm,
    val stepIndex: Int,
    /** Inferred receiver type for this step, or null when the chain can't be resolved. */
    val receiverType: String?,
    val memberName: String,
    val isProperty: Boolean,
    /** False when the receiver is a guess (e.g. `cond->` branches may not have applied). */
    val confident: Boolean,
)

/**
 * The single PSI-side model for chain-macro forms — every feature surface (type inference,
 * goto, references, hover, highlight suppression) resolves chain steps through here instead
 * of hand-walking `(.. …)` children.
 */
object InteropChains {

    fun meaningfulChildren(list: PsiElement): List<PsiElement> =
        JsInteropPsi.meaningfulChildren(list)

    /** Parses [list] as a chain-macro form, or null when its head is not a chain macro. */
    fun parse(list: ClList): ChainForm? {
        val kids = meaningfulChildren(list)
        val head = kids.firstOrNull() ?: return null
        val kind = ChainKind.fromHead(head.text?.trim()) ?: return null
        val root = kids.getOrNull(1) ?: return null
        var stepEls = kids.drop(2)
        // `cond->` family: elements after the root come in `test step` pairs — only the step
        // positions are chain steps.
        if (kind.isConditional) {
            stepEls = stepEls.filterIndexed { i, _ -> i % 2 == 1 }
        }
        return ChainForm(kind, list, root, stepEls.map { parseStep(it, kind) })
    }

    private fun parseStep(element: PsiElement, kind: ChainKind): ChainStep {
        if (element is ClList) {
            val kids = meaningfulChildren(element)
            val headEl = kids.firstOrNull() ?: return ChainStep.Opaque(element)
            val spec = InteropChainCore.parseStepHead(headEl.text?.trim().orEmpty(), kind)
                ?: return ChainStep.Opaque(element)
            return ChainStep.Interop(element, spec.copy(isListStep = true), headEl, kids.drop(1))
        }
        val spec = InteropChainCore.parseStepHead(element.text?.trim().orEmpty(), kind)
            ?: return ChainStep.Opaque(element)
        return ChainStep.Interop(element, spec, element, emptyList())
    }

    /**
     * Locates the chain step [symbol] belongs to: either a bare step directly in the chain
     * list, or the head of a `(.name args…)` list step. Returns null when the symbol is not a
     * member-carrying chain step (root, macro head, step arguments, non-chain forms).
     */
    fun locateStep(symbol: PsiElement): Pair<ChainForm, Int>? {
        val sym = JsInteropPsi.enclosingEditorSymbol(symbol) ?: symbol
        val parent = sym.parent as? ClList ?: return null

        // Bare step: the symbol sits directly in the chain list.
        parse(parent)?.let { form ->
            val idx = form.steps.indexOfFirst { (it as? ChainStep.Interop)?.anchor === sym }
            return if (idx >= 0) form to idx else null
        }

        // List-step head: the symbol heads a step list inside the chain list.
        val grand = parent.parent as? ClList ?: return null
        val form = parse(grand) ?: return null
        val idx = form.steps.indexOfFirst {
            it is ChainStep.Interop && it.element === parent && it.anchor === sym
        }
        return if (idx >= 0) form to idx else null
    }

    /** Cheap recognition: is [symbol] a member-carrying step of a chain form? */
    fun isChainStep(symbol: PsiElement): Boolean = locateStep(symbol) != null

    /**
     * Receiver type + member name for the chain step [symbol] sits in, walking prior steps
     * through the index. Returns null when the symbol is not a chain step; returns a context
     * with a null [ChainStepContext.receiverType] when it is a step but the chain (or its
     * root) cannot be resolved.
     */
    fun stepContext(
        symbol: PsiElement,
        index: JsSymbolIndex,
        resolveExprType: (PsiElement) -> String? = { JsResolveUtil.resolveType(it, index) },
    ): ChainStepContext? {
        val (form, stepIndex) = locateStep(symbol) ?: return null
        val step = form.steps[stepIndex] as? ChainStep.Interop ?: return null
        val receiverType = receiverTypeOf(form, stepIndex, index, resolveExprType)
        return ChainStepContext(
            form = form,
            stepIndex = stepIndex,
            receiverType = receiverType,
            memberName = step.spec.memberName,
            isProperty = step.spec.isProperty,
            confident = receiverType != null && !form.kind.isConditional,
        )
    }

    /**
     * Type of the entire chain form: the type after the last step (`..` / `->` / `some->` /
     * `->>`), or the root type (`doto` returns its first argument). Null for the `cond->`
     * family (branches may or may not apply) and whenever a step breaks the member chain.
     */
    fun resolveFormType(
        form: ChainForm,
        index: JsSymbolIndex,
        resolveExprType: (PsiElement) -> String?,
    ): String? {
        if (!form.kind.typeFlows) return null
        val rootType = resolveExprType(form.root)?.let { index.canonicalType(it) } ?: return null
        if (form.kind == ChainKind.DOTO) return rootType
        var current = rootType
        for (step in form.steps) {
            val interop = step as? ChainStep.Interop ?: return null
            current = stepResultType(form, interop, current, index, resolveExprType) ?: return null
        }
        return current
    }

    private fun receiverTypeOf(
        form: ChainForm,
        stepIndex: Int,
        index: JsSymbolIndex,
        resolveExprType: (PsiElement) -> String?,
    ): String? {
        val step = form.steps[stepIndex] as? ChainStep.Interop ?: return null

        // `->>` list steps: the threaded value is the LAST argument, so the member's receiver
        // is the step's own first argument, not the threaded chain value.
        if (form.kind.threadsLast && step.spec.isListStep && step.callArgs.isNotEmpty()) {
            return resolveExprType(step.callArgs.first())?.let { index.canonicalType(it) }
        }

        val rootType = resolveExprType(form.root)?.let { index.canonicalType(it) } ?: return null
        if (form.kind.rootReceiver) return rootType

        var current = rootType
        for (i in 0 until stepIndex) {
            val prior = form.steps[i] as? ChainStep.Interop ?: return null
            current = stepResultType(form, prior, current, index, resolveExprType) ?: return null
        }
        return current
    }

    private fun stepResultType(
        form: ChainForm,
        step: ChainStep.Interop,
        threadedType: String,
        index: JsSymbolIndex,
        resolveExprType: (PsiElement) -> String?,
    ): String? {
        val receiver = if (form.kind.threadsLast && step.spec.isListStep && step.callArgs.isNotEmpty()) {
            resolveExprType(step.callArgs.first())?.let { index.canonicalType(it) } ?: return null
        } else {
            threadedType
        }
        return InteropChainCore.advance(receiver, step.spec.memberName, index)
    }
}
