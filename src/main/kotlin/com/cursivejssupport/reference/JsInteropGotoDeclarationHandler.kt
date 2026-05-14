package com.cursivejssupport.reference

import com.cursivejssupport.debug.InteropLogView
import com.cursivejssupport.util.InteropDebugLog
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

class JsInteropGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        if (sourceElement == null) {
            InteropDebugLog.info("[interop-goto] handler: sourceElement=null offset=$offset")
            return null
        }
        InteropLogView.noteProject(sourceElement.project)
        val vf = sourceElement.containingFile?.virtualFile?.path
            ?: sourceElement.containingFile?.name
            ?: "?"
        InteropDebugLog.info(
            "[interop-goto] handler: offset=$offset file=$vf psi=${sourceElement::class.java.name}",
        )
        val targets = JsInteropNavigation.resolveGotoTargets(sourceElement, sourceElement.project)
        InteropDebugLog.info(
            "[interop-goto] handler: resultCount=${targets?.size ?: 0}",
        )
        return targets
    }

    override fun getActionText(context: DataContext): String? = null
}
