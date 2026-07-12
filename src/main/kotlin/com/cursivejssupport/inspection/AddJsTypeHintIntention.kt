package com.cursivejssupport.inspection

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.util.JsResolveUtil
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol

class AddJsTypeHintIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName(): String = "Add JavaScript type hint"
    override fun getText(): String = "Add inferred ^js/Type hint to interop receiver"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean = context(element) != null

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val context = context(element) ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(context.receiver.containingFile) ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(context.receiver.textRange.startOffset, "^js/${context.type} ")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun context(element: PsiElement): HintContext? {
        val symbol = PsiTreeUtil.getParentOfType(element, ClEditorSymbol::class.java, false) ?: return null
        if (!symbol.text.startsWith(".")) return null
        val list = symbol.parent as? ClList ?: return null
        val children = list.children.filter { it !is PsiWhiteSpace && it !is PsiComment && it.text !in setOf("(", ")") }
        if (children.firstOrNull() !== symbol) return null
        val receiver = children.getOrNull(1) ?: return null
        val index = JsSymbolIndex.getInstance(symbol.project)
        if (!index.isLoaded || JsResolveUtil.resolveTypeRef(receiver, index) != null) return null
        val member = symbol.text.removePrefix(".").removePrefix("-")
        val candidates = index.memberDeclarations(member).map { it.declaringType }.distinct()
        return candidates.singleOrNull()?.let { HintContext(receiver, it) }
    }

    private data class HintContext(val receiver: PsiElement, val type: String)
}
