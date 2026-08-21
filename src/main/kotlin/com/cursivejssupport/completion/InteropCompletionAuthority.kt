package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.util.InteropChains
import com.cursivejssupport.util.JsInteropPsi
import com.cursivejssupport.util.JsResolveUtil
import com.intellij.psi.PsiElement
import cursive.psi.api.ClList

/**
 * Decides when JS member completion is complete enough to own the popup.
 *
 * This deliberately depends on inferred receiver types and indexed members, not surface syntax.
 * If inference fails, other contributors remain untouched; if it succeeds, Clojure vars and
 * global prototype samples are not valid candidates for that member slot.
 */
internal object InteropCompletionAuthority {
    fun owns(
        context: InteropCompletionContext,
        index: JsSymbolIndex,
        position: PsiElement?,
    ): Boolean {
        if (!index.isLoaded) return false
        val receiverType = when (context) {
            is InteropCompletionContext.JsChainMember -> index.resolveJsChainType(context.receiverSegments)
            is InteropCompletionContext.DotMember -> dotReceiver(position)?.let { JsResolveUtil.resolveType(it, index) }
            is InteropCompletionContext.ChainStepForm ->
                position?.let { InteropChains.stepContext(it, index)?.receiverType }
            else -> null
        } ?: return false
        return index.resolveMembers(receiverType).isNotEmpty()
    }

    private fun dotReceiver(position: PsiElement?): PsiElement? {
        var current = position
        while (current != null) {
            if (current is ClList) {
                val children = JsInteropPsi.meaningfulChildren(current)
                val head = children.firstOrNull()?.text.orEmpty()
                if (head.startsWith(".") && !head.startsWith("..")) return children.getOrNull(1)
            }
            current = current.parent
        }
        return null
    }
}
