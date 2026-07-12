package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NpmBindingKind
import com.cursivejssupport.semantic.InteropSemanticService
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.util.JsResolveUtil
import com.cursivejssupport.util.JsInteropPsi
import com.intellij.openapi.components.service
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol

class JsInteropParameterInfoHandler : ParameterInfoHandler<PsiElement, JsMember> {

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val element = context.file.findElementAt((context.offset - 1).coerceAtLeast(0)) ?: return null
        val list = PsiTreeUtil.getParentOfType(element, ClList::class.java, false) ?: return null
        val overloads = overloads(list)
        if (overloads.isEmpty()) return null
        context.itemsToShow = overloads.toTypedArray()
        return list
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? {
        val element = context.file.findElementAt((context.offset - 1).coerceAtLeast(0)) ?: return null
        return PsiTreeUtil.getParentOfType(element, ClList::class.java, false)
    }

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        val list = parameterOwner as? ClList ?: return
        context.setCurrentParameter(argumentIndex(list, context.offset))
    }

    override fun updateUI(member: JsMember, context: ParameterInfoUIContext) {
        val params = member.params.joinToString(", ") { p ->
            buildString {
                if (p.rest) append("& ")
                append(p.name)
                if (p.optional) append('?')
                if (p.type.isNotBlank() && p.type != "any") append(": ").append(p.type)
            }
        }
        val text = "($params) → ${member.returns.ifBlank { member.type }}"
        val active = context.currentParameterIndex.coerceAtLeast(0)
        val ranges = parameterRanges(params)
        val range = ranges.getOrNull(active)
        context.setupUIComponentPresentation(
            text,
            range?.first ?: -1,
            range?.last?.plus(1) ?: -1,
            false,
            member.doc?.contains("@deprecated", ignoreCase = true) == true,
            false,
            context.defaultParameterColor,
        )
    }

    private fun overloads(list: ClList): List<JsMember> {
        val children = JsInteropPsi.meaningfulChildren(list)
        val head = children.firstOrNull() as? ClEditorSymbol ?: return emptyList()
        val index = JsSymbolIndex.getInstance(list.project)
        val text = head.text
        if (text.startsWith(".")) {
            val receiver = children.getOrNull(1)
            val type = JsResolveUtil.resolveTypeRef(receiver, index) ?: return emptyList()
            val semantic = type.semanticMembers.filter { it.name == text.removePrefix(".").removePrefix("-") }
                .map { JsMember(kind = it.kind, params = it.params, returns = it.returns, type = it.type, doc = it.doc) }
            if (semantic.isNotEmpty()) return semantic
            return index.resolveMembersOf(type.ref)[text.removePrefix(".").removePrefix("-")]?.overloads.orEmpty()
        }
        if (text.startsWith("js/")) return index.resolveFunctions(text.removePrefix("js/")).orEmpty()
        val semantics = list.project.service<InteropSemanticService>()
        val binding = semantics.bindings(list.containingFile)[text]
        if (binding != null && binding.kind in setOf(NpmBindingKind.REFER, NpmBindingKind.DEFAULT)) {
            val descriptor = semantics.exportType(
                list.containingFile, binding.packageName, binding.exportName ?: "default",
            )
            val semantic = descriptor?.callSignatures.orEmpty().map { signature ->
                JsMember(kind = "function", params = signature.params, returns = signature.returns.display())
            }
            if (semantic.isNotEmpty()) return semantic
            return index.resolveNpmExportMembers(binding.packageName, binding.exportName ?: "default").orEmpty()
        }
        return emptyList()
    }

    private fun argumentIndex(list: ClList, offset: Int): Int {
        val children = JsInteropPsi.meaningfulChildren(list)
        val head = children.firstOrNull() as? ClEditorSymbol ?: return 0
        val fixed = if (head.text.startsWith(".")) 2 else 1
        return children.drop(fixed).count { it.textRange.endOffset < offset }.coerceAtLeast(0)
    }

    private fun parameterRanges(params: String): List<IntRange> {
        if (params.isBlank()) return emptyList()
        val out = mutableListOf<IntRange>()
        var start = 1
        params.split(", ").forEach { p ->
            out += start..(start + p.length - 1)
            start += p.length + 2
        }
        return out
    }
}
