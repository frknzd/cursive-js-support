package com.cursivejssupport.npm

import com.intellij.javascript.nodejs.reference.NodeModuleManager
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

@Service(Service.Level.PROJECT)
class IntellijNpmResolutionService(private val project: Project) {

    fun resolveExports(anchor: PsiFile, packageName: String): List<PsiElement> {
        val anchorFile = anchor.virtualFile ?: return emptyList()
        project.basePath?.let { File(it) } ?: return emptyList()

        // Prefer declarations, then supplement them with the runtime module. IntelliJ can expose
        // only a subset of statically assigned CommonJS exports (for example diff@8), whereas the
        // package's .d.ts contains the complete public API.
        val moduleFiles = LinkedHashSet<VirtualFile>()
        project.service<NpmPackageResolver>().typingsEntryFiles(packageName, anchorFile.path)
            .mapNotNull { VirtualFileManager.getInstance().findFileByNioPath(it.toPath()) }
            .forEach(moduleFiles::add)

        val moduleManager = NodeModuleManager.getInstance(project)
        val moduleInfo = moduleManager.resolveNonPathModule(packageName, anchorFile)
            ?: moduleManager.resolveCoreModule(packageName, anchorFile)
        (moduleInfo?.moduleMainFile ?: moduleInfo?.moduleSourceRoot)?.let(moduleFiles::add)

        val named = LinkedHashMap<String, PsiElement>()
        val unnamed = mutableListOf<PsiElement>()
        moduleFiles.flatMap(::exportsFrom).forEach { element ->
            val name = (element as? com.intellij.lang.javascript.psi.JSNamedElement)?.name
            if (name == null) unnamed += element else named.putIfAbsent(name, element)
        }
        return named.values + unnamed
    }

    private fun exportsFrom(moduleFile: VirtualFile): List<PsiElement> {
        val psiFile = PsiManager.getInstance(project).findFile(moduleFile)
        if (psiFile is JSFile) {
            val exports = JSResolveUtil.getExportedElements(psiFile) ?: emptyList()
            // Preserve export identity. Flattening a sole class/object changes a default export
            // into named exports and makes CLJS `:default`/`:as` semantics incorrect.
            if (exports.size == 1) {
                val single = exports.first()
                if (single.javaClass.simpleName.contains("Namespace")) {
                    IntellijNamespaceMembers.members(single).takeIf(List<PsiElement>::isNotEmpty)?.let { return it }
                }
            }
            return exports
        }
        return emptyList()
    }

    fun resolveExport(anchor: PsiFile, packageName: String, exportName: String): PsiElement? {
        val exports = resolveExports(anchor, packageName)
        return exports.firstOrNull { (it as? com.intellij.lang.javascript.psi.JSNamedElement)?.name == exportName }
            ?: if (exportName == "default") exports.singleOrNull() else null
    }

    fun hasExport(anchor: PsiFile, packageName: String, exportName: String): Boolean =
        resolveExport(anchor, packageName, exportName) != null

    fun discoverPackages(anchor: PsiFile): Set<String> {
        val anchorFile = anchor.virtualFile ?: return emptySet()
        val moduleManager = NodeModuleManager.getInstance(project)
        return moduleManager.collectVisibleNodeModules(anchorFile).map { it.name }.toSet()
    }
}

/** The only compatibility reflection left for JavaScript PSI namespace implementations. */
private object IntellijNamespaceMembers {
    private val methods = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method?>()

    fun members(namespace: PsiElement): List<PsiElement> = runCatching {
        val method = methods.computeIfAbsent(namespace.javaClass) { type ->
            type.methods.firstOrNull { it.name in setOf("getMembers", "members") && it.parameterCount == 0 }
        } ?: return emptyList()
        when (val value = method.invoke(namespace)) {
            is Array<*> -> value.filterIsInstance<PsiElement>()
            is Collection<*> -> value.filterIsInstance<PsiElement>()
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}
