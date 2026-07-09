package com.cursivejssupport.npm

import com.cursivejssupport.parser.JsParam
import com.cursivejssupport.types.JsMemberDescriptor
import com.cursivejssupport.types.JsTypeSource
import com.intellij.lang.javascript.psi.JSType
import com.intellij.lang.javascript.psi.JSTypeOwner
import com.intellij.lang.javascript.psi.JSNamedElement
import com.intellij.lang.javascript.psi.resolve.JSResolveUtil
import com.intellij.lang.javascript.psi.types.JSFunctionTypeImpl
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import java.util.concurrent.ConcurrentHashMap

/**
 * [JsTypeSource] backed by the IntelliJ JavaScript plugin's own type evaluation. Registered
 * ONLY via `META-INF/javascript-support.xml`, so this class never loads when the JavaScript
 * plugin is absent.
 *
 * The JS plugin's record-type APIs shift between IDE releases — every entry point is wrapped
 * in a broad catch and degrades to `null` (callers fall back to the bundled `.d.ts` index).
 * Lookups are time-budgeted and cached against [PsiModificationTracker].
 */
class JsPluginTypeSource : JsTypeSource {

    private val log = logger<JsPluginTypeSource>()

    private data class CacheKey(val pkg: String, val export: String, val path: List<String>)
    private class CacheEntry(val stamp: Long, val members: List<JsMemberDescriptor>?)

    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    private companion object {
        const val TIME_BUDGET_MS = 75L
        const val CACHE_CAP = 512
    }

    override fun npmExportMembers(
        file: PsiFile,
        packageName: String,
        exportName: String,
        memberPath: List<String>,
    ): List<JsMemberDescriptor>? {
        val project = file.project
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        val key = CacheKey(packageName, exportName, memberPath)
        cache[key]?.let { if (it.stamp == stamp) return it.members }

        val members = guarded {
            val deadline = System.currentTimeMillis() + TIME_BUDGET_MS
            val export = findExport(file, packageName, exportName) ?: return@guarded null
            var type = elementType(export) ?: return@guarded null
            for (segment in memberPath) {
                if (System.currentTimeMillis() > deadline) return@guarded null
                type = propertyType(type, segment) ?: return@guarded null
            }
            if (System.currentTimeMillis() > deadline) return@guarded null
            recordMembers(type)
        }
        if (cache.size >= CACHE_CAP) cache.clear()
        cache[key] = CacheEntry(stamp, members)
        return members
    }

    override fun npmExportTypeDisplay(file: PsiFile, packageName: String, exportName: String): String? =
        guarded {
            val export = findExport(file, packageName, exportName) ?: return@guarded null
            elementType(export)?.getTypeText(JSType.TypeTextFormat.PRESENTABLE)
        }

    // ─── JS plugin internals (all guarded) ──────────────────────────────────

    private fun findExport(file: PsiFile, packageName: String, exportName: String): PsiElement? {
        val service = file.project.service<IntellijNpmResolutionService>()
        val exports = service.resolveExports(file, packageName)
        if (exports.isEmpty()) return null
        return exports.firstOrNull { (it as? JSNamedElement)?.name == exportName }
            ?: if (exportName == "default") exports.singleOrNull() else null
    }

    private fun elementType(element: PsiElement): JSType? =
        (element as? JSTypeOwner)?.jsType ?: JSResolveUtil.getElementJSType(element)

    private fun propertyType(type: JSType, memberName: String): JSType? {
        val signature = type.asRecordType().findPropertySignature(memberName) ?: return null
        val t = signature.jsType ?: return null
        return unwrapFunctionReturn(t) ?: t
    }

    private fun unwrapFunctionReturn(type: JSType): JSType? =
        (type as? JSFunctionTypeImpl)?.returnType

    private fun recordMembers(type: JSType): List<JsMemberDescriptor>? {
        val properties = type.asRecordType().properties
        if (properties.isEmpty()) return null
        val out = ArrayList<JsMemberDescriptor>(properties.size)
        for (p in properties) {
            val name = p.memberName
            if (name.isEmpty()) continue
            val pType = p.jsType
            val navigatable = p.memberSource.singleElement
            if (pType is JSFunctionTypeImpl) {
                out.add(
                    JsMemberDescriptor(
                        name = name,
                        kind = "method",
                        params = pType.parameters.map { param ->
                            JsParam(
                                name = param.name ?: "arg",
                                type = param.simpleType?.getTypeText(JSType.TypeTextFormat.PRESENTABLE) ?: "any",
                                optional = param.isOptional,
                                rest = param.isRest,
                            )
                        },
                        returns = pType.returnType?.getTypeText(JSType.TypeTextFormat.PRESENTABLE) ?: "any",
                        navigatable = navigatable,
                    ),
                )
            } else {
                out.add(
                    JsMemberDescriptor(
                        name = name,
                        kind = "property",
                        type = pType?.getTypeText(JSType.TypeTextFormat.PRESENTABLE) ?: "any",
                        navigatable = navigatable,
                    ),
                )
            }
        }
        return out.ifEmpty { null }
    }

    /** Broad guard: JS-plugin internals may change or throw; always degrade to null. */
    private inline fun <T> guarded(block: () -> T?): T? =
        try {
            block()
        } catch (e: Throwable) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            log.debug("JsPluginTypeSource degraded to index fallback", e)
            null
        }
}
