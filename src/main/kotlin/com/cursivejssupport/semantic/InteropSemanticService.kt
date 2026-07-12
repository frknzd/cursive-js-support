package com.cursivejssupport.semantic

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.IntellijNpmResolutionService
import com.cursivejssupport.npm.NpmBinding
import com.cursivejssupport.npm.NpmPackageResolver
import com.cursivejssupport.npm.NsAliasResolver
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.types.JsMemberDescriptor
import com.cursivejssupport.types.JsTypeDescriptor
import com.cursivejssupport.types.JsTypeSources
import com.intellij.lang.javascript.psi.JSNamedElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

data class SemanticExport(val name: String, val declaration: PsiElement?)

/** Single editor-facing gateway for npm bindings and structured JavaScript semantics. */
@Service(Service.Level.PROJECT)
class InteropSemanticService {
    fun bindings(file: PsiFile): Map<String, NpmBinding> = NsAliasResolver.resolveAliases(file)
    fun bindingDeclaration(file: PsiFile, name: String): PsiElement? = NsAliasResolver.resolveAliasDeclaration(file, name)

    fun packages(file: PsiFile): Set<String> = buildSet {
        addAll(file.project.service<NpmPackageResolver>().discoverAllDependencyPackageNames(file.virtualFile?.path))
        addAll(file.project.service<IntellijNpmResolutionService>().discoverPackages(file))
    }

    fun exports(file: PsiFile, packageName: String): List<SemanticExport> {
        val live = file.project.service<IntellijNpmResolutionService>().resolveExports(file, packageName)
            .mapNotNull { element -> (element as? JSNamedElement)?.name?.let { SemanticExport(it, element) } }
        val indexed = JsSymbolIndex.getInstance(file.project).npmExportNames(packageName)
        return mergeSemanticExports(live, indexed)
    }

    fun exportNames(file: PsiFile, packageName: String): List<String> =
        exports(file, packageName).map(SemanticExport::name).distinct()

    fun exportDeclaration(file: PsiFile, packageName: String, exportName: String): PsiElement? =
        file.project.service<IntellijNpmResolutionService>().resolveExport(file, packageName, exportName)

    fun exportType(
        file: PsiFile,
        packageName: String,
        exportName: String,
        memberPath: List<String> = emptyList(),
    ): JsTypeDescriptor? = JsTypeSources.npmExportType(file, packageName, exportName, memberPath)

    fun exportMembers(
        file: PsiFile,
        packageName: String,
        exportName: String,
        memberPath: List<String> = emptyList(),
    ): List<JsMemberDescriptor> {
        JsTypeSources.npmExportMembers(file, packageName, exportName, memberPath)?.let { return it }
        val index = JsSymbolIndex.getInstance(file.project)
        var type = index.resolveNpmExportType(packageName, exportName) ?: return emptyList()
        memberPath.forEach { segment ->
            val member = index.resolveMember(type, segment)?.first ?: return emptyList()
            type = index.memberValueTypeName(member).ifEmpty { return emptyList() }
        }
        return index.resolveMembers(type).flatMap { (name, resolved) ->
            resolved.overloads.map { it.descriptor(name) }
        }
    }

    fun hasExport(file: PsiFile, packageName: String, exportName: String): Boolean =
        exportNames(file, packageName).let { exportName in it || exportName == "default" && it.size == 1 }

    private fun JsMember.descriptor(name: String) = JsMemberDescriptor(
        name = name, kind = kind, params = params, returns = returns, type = type, doc = doc,
    )
}

/** Keeps live declaration targets without allowing a partial IDE result to hide indexed exports. */
internal fun mergeSemanticExports(
    live: List<SemanticExport>,
    indexedNames: Collection<String>,
): List<SemanticExport> = buildMap<String, SemanticExport> {
    indexedNames.forEach { put(it, SemanticExport(it, null)) }
    live.forEach { put(it.name, it) }
}.values.toList()
