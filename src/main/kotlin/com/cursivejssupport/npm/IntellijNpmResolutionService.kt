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
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.psi.PsiNamedElement
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceOrNull
import java.io.File

@Service(Service.Level.PROJECT)
class IntellijNpmResolutionService(private val project: Project) {

    fun resolveExports(anchor: PsiFile, packageName: String): List<PsiElement> {
        val anchorFile = anchor.virtualFile ?: return emptyList()
        val projectDir = project.basePath?.let { File(it) } ?: return emptyList()

        val resolver = project.service<NpmPackageResolver>()
        val typingsFile = resolver.typingsEntryFile(packageName, anchorFile.path)
        
        var moduleFile: com.intellij.openapi.vfs.VirtualFile? = null
        if (typingsFile != null) {
            moduleFile = VirtualFileManager.getInstance().findFileByNioPath(typingsFile.toPath())
        }

        if (moduleFile == null) {
            val moduleManager = NodeModuleManager.getInstance(project)
            val moduleInfo = moduleManager.resolveNonPathModule(packageName, anchorFile)
                ?: moduleManager.resolveCoreModule(packageName, anchorFile)
            moduleFile = try {
                val clazz = moduleInfo?.javaClass
                val method = clazz?.methods?.find { it.name == "getModuleSourceFile" || it.name == "getSourceFile" }
                method?.invoke(moduleInfo) as? com.intellij.openapi.vfs.VirtualFile
            } catch (e: Exception) { null }
        }
        
        if (moduleFile == null) return emptyList()

        val psiFile = PsiManager.getInstance(project).findFile(moduleFile)
        if (psiFile is JSFile) {
            val exports = JSResolveUtil.getExportedElements(psiFile) ?: emptyList()
            val unwrapped = mutableListOf<PsiElement>()
            for (export in exports) {
                if (export is JSObjectLiteralExpression) {
                    unwrapped.addAll(export.properties)
                } else if (export is JSClass) {
                    if (exports.size == 1) {
                        unwrapped.addAll(export.members.toList())
                    } else {
                        unwrapped.add(export)
                    }
                } else {
                    unwrapped.add(export)
                }
            }
            
            if (unwrapped.size == 1) {
                val single = unwrapped.first()
                if (single.javaClass.simpleName.contains("Namespace")) {
                    try {
                        val getMembers = single.javaClass.methods.find { it.name == "getMembers" || it.name == "members" }
                        val members = getMembers?.invoke(single) as? Array<*>
                        if (members != null && members.isNotEmpty()) {
                            return members.filterIsInstance<PsiElement>()
                        }
                    } catch (e: Exception) { }
                }
            }
            return unwrapped
        }
        return emptyList()
    }

    fun discoverPackages(anchor: PsiFile): Set<String> {
        val anchorFile = anchor.virtualFile ?: return emptySet()
        val moduleManager = NodeModuleManager.getInstance(project)
        return try {
            val modules = moduleManager.collectVisibleNodeModules(anchorFile)
            modules.mapNotNull {
                if (it == null) return@mapNotNull null
                val clazz = it.javaClass
                val method = clazz.methods.find { m -> m.name == "getName" || m.name == "getModuleName" || m.name == "name" }
                method?.invoke(it) as? String
            }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
