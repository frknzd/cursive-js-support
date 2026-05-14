package com.cursivejssupport.reference

import com.cursivejssupport.parser.JsMember
import com.intellij.lang.Language
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.impl.light.LightElement

class JsSymbolPsiElement(
    manager: PsiManager,
    language: Language,
    private val name: String,
    val type: String?,
    val doc: String?,
    val member: JsMember? = null,
    val packageName: String? = null,
    /** When [packageName] is set, typings export key (e.g. `default`). */
    val npmExportName: String? = null,
    private val navigationTarget: PsiElement? = null,
) : LightElement(manager, language), PsiQualifiedNamedElement, PsiNameIdentifierOwner, NavigatablePsiElement {

    override fun getName(): String = name
    override fun getQualifiedName(): String? = name
    override fun toString(): String = "JsSymbol:$name"
    override fun getParent(): PsiElement? = null
    override fun getText(): String = name

    override fun getNameIdentifier(): PsiElement? = this
    override fun setName(name: String): PsiElement = this

    override fun getNavigationElement(): PsiElement = navigationTarget ?: this

    override fun navigate(requestFocus: Boolean) {
        val target = navigationTarget ?: return
        val nav = target as? NavigatablePsiElement
            ?: target.navigationElement as? NavigatablePsiElement
        nav?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean {
        val target = navigationTarget ?: return false
        val nav = target as? NavigatablePsiElement
            ?: target.navigationElement as? NavigatablePsiElement
        return nav?.canNavigate() == true
    }

    override fun canNavigateToSource(): Boolean = canNavigate()
}
