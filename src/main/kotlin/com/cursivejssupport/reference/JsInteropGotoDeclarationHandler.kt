package com.cursivejssupport.reference

import com.cursivejssupport.npm.NsAliasResolver
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

class JsInteropGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        // sourceElement is the exact token you clicked on (e.g., "Markdown", "remarkGfm/foo", ".setStart")
        val text = sourceElement.text

        // Ignore structural characters
        if (text.isBlank() || text.startsWith("\"") || text.startsWith("(") || text.startsWith("[")) {
            return null
        }

        val file = sourceElement.containingFile ?: return null
        val aliases = NsAliasResolver.resolveAliases(file)

        // =====================================================================
        // 1. ALIASES & NPM EXPORTS (e.g., "Markdown" or "remarkGfm/foo")
        // =====================================================================
        val namespace = if (text.contains("/")) text.substringBefore("/") else text

        if (aliases.containsKey(namespace)) {
            // Find the exact PsiElement in the ns form and jump to it!
            val target = NsAliasResolver.resolveAliasDeclaration(file, namespace)
            if (target != null) {
                return arrayOf(target)
            }
        }

        // =====================================================================
        // 2. BROWSER GLOBALS (e.g., "js/document")
        // =====================================================================
        if (text.startsWith("js/")) {
            val globalName = text.substringAfter("js/")

            // TODO: To jump to the actual JS/TS file, your JsSymbolIndex needs to
            // return the PsiElement of the declaration.
            // Example:
            // val targetElement = JsSymbolIndex.getInstance().getGlobalPsiElement(globalName)
            // if (targetElement != null) return arrayOf(targetElement)
        }

        // =====================================================================
        // 3. JS METHODS & PROPERTIES (e.g., ".setStart", ".-className")
        // =====================================================================
        if (text.startsWith(".") || text.startsWith(".-")) {
            val memberName = text.removePrefix(".-").removePrefix(".")

            // TODO: Just like globals, your index needs to know WHERE this method
            // is defined on the hard drive to jump to it.
            // Example:
            // val targetElement = JsSymbolIndex.getInstance().getMemberPsiElement(memberName)
            // if (targetElement != null) return arrayOf(targetElement)
        }

        // Return null to let Cursive handle standard Clojure symbols
        return null
    }

    override fun getActionText(context: PsiElement?): String? = null
}