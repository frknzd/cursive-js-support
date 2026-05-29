package com.cursivejssupport.reference

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NsAliasResolver
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.PsiWhiteSpace
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import cursive.psi.api.ClList
import cursive.psi.api.symbols.ClSymbol

class JsSymbolReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(ClSymbol::class.java),
            JsSymbolReferenceProvider(),
            PsiReferenceRegistrar.HIGHER_PRIORITY,
        )
    }
}

private class JsSymbolReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val symbol = element as? ClSymbol ?: return emptyArray()

        val index = JsSymbolIndex.getInstance()
        val text = symbol.text ?: ""
        val trimmed = text.trim()
        val ns = symbol.namespace

        val file = symbol.containingFile ?: return emptyArray()
        val npmAliases = NsAliasResolver.resolveAliases(file)
        val isNpmAlias = ns != null && npmAliases.containsKey(ns)
        val bareNpmAlias = ns == null && npmAliases.containsKey(trimmed)
        val isDirectGoogAccess = ns != null && index.isKnownGoogNamespace(ns)
        val isDotDotStep = isInsideDotDotChain(symbol)

        val looksLikeJsInterop =
            trimmed == "js" ||
                trimmed.startsWith("js/") ||
                ns == "js" ||
                trimmed.startsWith(".") ||
                trimmed.startsWith(".-") ||
                isNpmAlias ||
                bareNpmAlias ||
                isDirectGoogAccess ||
                isDotDotStep

        if (!looksLikeJsInterop) return emptyArray()

        val needsLoadedDomIndex =
            trimmed == "js" ||
                trimmed.startsWith("js/") ||
                ns == "js" ||
                trimmed.startsWith(".") ||
                trimmed.startsWith(".-") ||
                isDotDotStep

        if (!index.isLoaded && needsLoadedDomIndex) return emptyArray()

        return arrayOf(JsSymbolReference(symbol))
    }
}

/** Returns true when [symbol] is a method/property step at position ≥ 2 inside `(.. root ...)`. */
private fun isInsideDotDotChain(symbol: ClSymbol): Boolean {
    val list = symbol.parent as? ClList ?: return false
    val children = list.children.filter {
        it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")"
    }
    if (children.isEmpty() || children[0].text != "..") return false
    val rootElement = children.getOrNull(1) ?: return false
    return symbol.textOffset > rootElement.textOffset
}

private class JsSymbolReference(element: ClSymbol) : PsiReferenceBase<ClSymbol>(element, TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? =
        JsInteropNavigation.resolveClSymbol(element, element.project)

    override fun getVariants(): Array<Any> = emptyArray()
}
