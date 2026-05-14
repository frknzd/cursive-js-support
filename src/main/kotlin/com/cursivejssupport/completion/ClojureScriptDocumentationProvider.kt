package com.cursivejssupport.completion

import com.cursivejssupport.documentation.InteropDocFormatter
import com.cursivejssupport.documentation.InteropDocResolver
import com.cursivejssupport.documentation.InteropDocSubject
import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.reference.JsSymbolPsiElement
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import cursive.psi.impl.symbols.ClEditorSymbol

/**
 * Thin shell: locate the hovered `ClEditorSymbol` (or [JsSymbolPsiElement] coming from goto
 * navigation), classify it via [InteropDocResolver], hand it to [InteropDocFormatter].
 *
 * All TS/dts knowledge lives in the resolver, all rendering in the formatter — this provider only
 * decides which element to feed in.
 */
class ClojureScriptDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val index = JsSymbolIndex.getInstance()
        if (!index.isLoaded) return null

        if (element is JsSymbolPsiElement) {
            return InteropDocFormatter.format(subjectForLightElement(element))
        }

        val symbol = findClojureSymbol(originalElement) ?: findClojureSymbol(element) ?: return null
        val subject = InteropDocResolver.resolve(symbol, index)
        return InteropDocFormatter.format(subject)
    }

    private fun findClojureSymbol(element: PsiElement?): ClEditorSymbol? {
        if (element == null) return null
        return PsiTreeUtil.getParentOfType(element, ClEditorSymbol::class.java, false)
    }

    /**
     * Maps a [JsSymbolPsiElement] (built by goto navigation) onto the same subject model used by
     * editor-symbol hover so both code paths render identically.
     */
    private fun subjectForLightElement(element: JsSymbolPsiElement): InteropDocSubject {
        val member = element.member
        val pkg = element.packageName
        return when {
            pkg != null -> InteropDocSubject.NpmExport(
                packageName = pkg,
                exportName = element.npmExportName ?: element.name,
                type = element.type,
            )
            member != null && element.type != null && element.type != "function" -> InteropDocSubject.Member(
                name = element.name,
                asProperty = member.kind != "method",
                declaringType = InteropDocResolver.sanitizeTypeName(element.type),
                member = member,
            )
            member != null -> InteropDocSubject.JsFunction(
                name = element.name,
                overload = member,
                overloadCount = 1,
            )
            else -> InteropDocSubject.Unknown
        }
    }
}
