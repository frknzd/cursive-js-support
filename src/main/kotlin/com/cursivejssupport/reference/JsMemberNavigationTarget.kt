package com.cursivejssupport.reference

import com.cursivejssupport.index.BundledDomLibs
import com.cursivejssupport.parser.JsLocation
import com.cursivejssupport.parser.JsMember
import com.intellij.lang.Language
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.light.LightElement
import javax.swing.Icon
import java.io.File

/**
 * Wraps a resolved `.d.ts` leaf so "Choose Declaration" can show the declaring interface and a
 * rich signature line. Icon is suppressed deliberately — the row is text-only by design so the
 * type / return-type / declaring interface stay readable in the popup.
 */
class JsMemberNavigationTarget(
    manager: PsiManager,
    language: Language,
    private val delegate: PsiElement,
    private val declaringInterface: String,
    private val deprecatedHint: Boolean,
    private val location: JsLocation? = null,
    private val member: JsMember? = null,
) : LightElement(manager, language), NavigatablePsiElement {

    /** Declaring interface name (e.g. `Document`) for presentation / resolve metadata. */
    val declaringTypeName: String get() = declaringInterface

    /** Underlying `.d.ts` identifier (unwrap from [JsMemberNavigationTarget] for delegation). */
    val indexedLeaf: PsiElement get() = delegate

    /** The parsed member metadata behind this target; null for non-member wrappers. */
    val jsMember: JsMember? get() = member

    override fun getText(): String = delegate.text
    override fun getContainingFile(): PsiFile = delegate.containingFile
    override fun getNavigationElement(): PsiElement = delegate
    override fun getParent(): PsiElement? = null

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String {
            val base = buildString {
                append(memberSignature(delegate.text, member))
                append("  —  ")
                append(declaringInterface)
            }
            return if (deprecatedHint) "$base  —  @deprecated" else base
        }

        override fun getLocationString(): String? {
            val raw = location?.filePath ?: delegate.containingFile.virtualFile?.path ?: return null
            val name = File(raw.replace('\\', '/')).name
            val offset = location?.offset
            return if (offset != null) "$name:$offset" else name
        }

        override fun getIcon(unused: Boolean): Icon? = null
    }

    override fun navigate(requestFocus: Boolean) {
        val loc = location
        if (loc != null) {
            val vf = BundledDomLibs.resolveVirtualFile(loc.filePath)
                ?: LocalFileSystem.getInstance().findFileByPath(loc.filePath)
            if (vf != null) {
                OpenFileDescriptor(project, vf, loc.offset).navigate(requestFocus)
                return
            }
        }
        val nav = delegate as? NavigatablePsiElement
            ?: delegate.navigationElement as? NavigatablePsiElement
        nav?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean {
        val loc = location
        if (loc != null) {
            val vf = BundledDomLibs.resolveVirtualFile(loc.filePath)
                ?: LocalFileSystem.getInstance().findFileByPath(loc.filePath)
            if (vf != null) return true
        }
        val nav = delegate as? NavigatablePsiElement
            ?: delegate.navigationElement as? NavigatablePsiElement
        return nav?.canNavigate() == true
    }

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun toString(): String = "JsMemberNav:${delegate.text} ($declaringInterface)"

    /**
     * Formats the human-readable signature line for a member, like:
     * - `createTreeWalker(root: Node, whatToShow?: number, filter?: NodeFilter): TreeWalker`
     * - `commonAncestorContainer: Node`
     * Falls back to bare name when no [JsMember] metadata is available.
     */
    private fun memberSignature(name: String, member: JsMember?): String {
        if (member == null) return name
        return when (member.kind) {
            "method" -> {
                val params = member.params.joinToString(", ") { p ->
                    when {
                        p.rest -> "...${p.name}: ${p.type}"
                        p.optional -> "${p.name}?: ${p.type}"
                        else -> "${p.name}: ${p.type}"
                    }
                }
                "$name($params): ${member.returns}"
            }
            else -> "$name: ${member.type}"
        }
    }
}
