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
import java.io.File

@Service(Service.Level.PROJECT)
class IntellijNpmResolutionService(private val project: Project) {

    fun resolveExports(anchor: PsiFile, packageName: String): List<PsiElement> {
        val anchorFile = anchor.virtualFile ?: return emptyList()
        project.basePath?.let { File(it) } ?: return emptyList()

        // Ask the JavaScript plugin first. This is the same Node/TypeScript module resolver used
        // by JS and TS source files, so exports conditions, tsconfig mappings, package managers,
        // and the IDE's TypeScript service stay authoritative.
        val moduleManager = NodeModuleManager.getInstance(project)
        val moduleInfo = moduleManager.resolveNonPathModule(packageName, anchorFile)
            ?: moduleManager.resolveCoreModule(packageName, anchorFile)
        var moduleFile = moduleInfo?.moduleMainFile ?: moduleInfo?.moduleSourceRoot

        // Deterministic fallback for test fixtures and declarations the IDE has not indexed yet.
        if (moduleFile == null) {
            val typingsFile = project.service<NpmPackageResolver>().typingsEntryFile(packageName, anchorFile.path)
            if (typingsFile != null) {
                moduleFile = VirtualFileManager.getInstance().findFileByNioPath(typingsFile.toPath())
            }
        }
        
        if (moduleFile == null) return emptyList()

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
