package com.cursivejssupport.reference

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.light.LightElement

/**
 * Wraps a resolved `.d.ts` leaf so "Choose Declaration" can show the declaring interface (e.g. Element vs SVGElement).
 */
class JsMemberNavigationTarget(
    manager: PsiManager,
    language: Language,
    private val delegate: PsiElement,
    private val declaringInterface: String,
    private val deprecatedHint: Boolean,
) : LightElement(manager, language), NavigatablePsiElement {

    /** Declaring interface name (e.g. `Document`) for presentation / resolve metadata. */
    val declaringTypeName: String get() = declaringInterface

    override fun getText(): String = delegate.text
    override fun getContainingFile(): PsiFile = delegate.containingFile
    override fun getNavigationElement(): PsiElement = delegate

    /** Underlying `.d.ts` identifier (unwrap from [JsMemberNavigationTarget] for delegation). */
    val indexedLeaf: PsiElement get() = delegate

    override fun getParent(): PsiElement? = null

    override fun getPresentation(): ItemPresentation {
        return object : ItemPresentation {
            override fun getPresentableText(): String {
                val base = "${delegate.text} ($declaringInterface)"
                return if (deprecatedHint) "$base — @deprecated" else base
            }

            override fun getLocationString(): String? = delegate.containingFile.virtualFile?.name

            override fun getIcon(unused: Boolean) = AllIcons.Nodes.Method
        }
    }

    override fun navigate(requestFocus: Boolean) {
        val nav = delegate as? NavigatablePsiElement
            ?: delegate.navigationElement as? NavigatablePsiElement
        nav?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean {
        val nav = delegate as? NavigatablePsiElement
            ?: delegate.navigationElement as? NavigatablePsiElement
        return nav?.canNavigate() == true
    }

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun toString(): String = "JsMemberNav:${delegate.text} ($declaringInterface)"
}
