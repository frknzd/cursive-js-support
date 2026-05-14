package com.cursivejssupport.index

import com.cursivejssupport.parser.JsInterface
import com.cursivejssupport.parser.JsLocation
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.parser.JsVariableInfo
import com.cursivejssupport.parser.ParsedSymbols
import com.cursivejssupport.reference.JsMemberNavigationTarget
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

@Service(Service.Level.APP)
class JsSymbolIndex {

    private val log = logger<JsSymbolIndex>()

    private val globals = ConcurrentHashMap<String, JsVariableInfo>()
    private val interfaces = ConcurrentHashMap<String, JsInterface>()
    private val functions = ConcurrentHashMap<String, List<JsMember>>()

    // CHANGED: Now maps PackageName -> (ExportName -> Location)
    private val npmExports = ConcurrentHashMap<String, MutableMap<String, JsLocation?>>()

    /** Member name → up to N sample (declaring interface, first overload) for fast completion when receiver type is unknown. */
    private val memberSamples = ConcurrentHashMap<String, MutableList<Pair<String, JsMember>>>()

    private val _loaded = AtomicBoolean(false)
    val isLoaded: Boolean get() = _loaded.get()

    fun claimLoad(): Boolean = _loaded.compareAndSet(false, true)
    fun setLoaded(value: Boolean) { _loaded.set(value) }

    fun load(symbols: ParsedSymbols) {
        for ((name, iface) in symbols.interfaces) {
            interfaces.merge(name, iface) { existing, incoming ->
                val merged = existing.members.toMutableMap()
                for ((memberName, overloads) in incoming.members) {
                    merged.merge(memberName, overloads) { a, b -> a + b }
                }
                JsInterface(
                    location = existing.location ?: incoming.location,
                    members = merged
                )
            }
        }
        for ((name, info) in symbols.variables) globals[name] = info
        for ((name, overloads) in symbols.functions) functions.merge(name, overloads) { a, b -> a + b }
        rebuildMemberSamples()
    }

    fun loadNpmPackage(packageName: String, symbols: ParsedSymbols) {
        if (symbols.interfaces.isNotEmpty()) {
            for ((name, iface) in symbols.interfaces) {
                interfaces.merge(name, iface) { existing, incoming ->
                    val merged = existing.members.toMutableMap()
                    for ((memberName, overloads) in incoming.members) {
                        merged.merge(memberName, overloads) { a, b -> a + b }
                    }
                    JsInterface(
                        location = existing.location ?: incoming.location,
                        members = merged
                    )
                }
            }
            rebuildMemberSamples()
        }

        val exports = mutableMapOf<String, JsLocation?>()

        // Extract locations for all exports
        symbols.variables.forEach { (name, info) -> exports[name] = info.location }
        symbols.functions.forEach { (name, overloads) -> exports[name] = overloads.firstOrNull()?.location }

        if (exports.isNotEmpty()) {
            npmExports[packageName] = exports
        }
    }

    private fun rebuildMemberSamples() {
        memberSamples.clear()
        for ((typeName, iface) in interfaces) {
            for ((memberName, overloads) in iface.members) {
                val first = overloads.firstOrNull() ?: continue
                val bucket = memberSamples.computeIfAbsent(memberName) { mutableListOf() }
                if (bucket.size < 8) {
                    bucket.add(typeName to first)
                }
            }
        }
    }

    /**
     * For completion when receiver type is unknown: members whose name starts with [namePrefix], capped.
     */
    fun sampleMembersByNamePrefix(namePrefix: String, limit: Int = 220): List<Triple<String, String, JsMember>> {
        val keys = if (namePrefix.isEmpty()) memberSamples.keys.sorted().take(limit)
        else memberSamples.keys.filter { it.startsWith(namePrefix) }.sorted().take(limit)

        val out = ArrayList<Triple<String, String, JsMember>>(keys.size)
        for (key in keys) {
            val samples = memberSamples[key] ?: continue
            val (t, m) = samples.first()
            out.add(Triple(key, t, m))
        }
        return out
    }

    // ─── PSI Resolution API (For Go To Declaration) ─────────────────────────

    fun getGlobalPsiElements(project: Project, name: String): Array<PsiElement>? {
        val locations = mutableListOf<JsLocation>()
        globals[name]?.location?.let { locations.add(it) }
        functions[name]?.forEach { it.location?.let { loc -> locations.add(loc) } }
        val elements = locations.mapNotNull { resolveLocation(project, it) }
        return if (elements.isNotEmpty()) elements.toTypedArray() else null
    }

    fun getMemberPsiElements(project: Project, typeName: String, memberName: String): Array<PsiElement>? {
        val overloads = interfaces[typeName]?.members?.get(memberName) ?: return null
        val out = overloads.mapNotNull { m ->
            val loc = m.location ?: return@mapNotNull null
            val resolved = resolveLocation(project, loc) ?: return@mapNotNull null
            wrapIndexedMember(project, resolved, typeName, m)
        }
        return if (out.isNotEmpty()) out.toTypedArray() else null
    }

    fun getNpmExportPsiElements(project: Project, packageName: String, exportName: String): Array<PsiElement>? {
        val exportsMap = npmExports[packageName] ?: return null
        if (!exportsMap.containsKey(exportName)) return null
        val location = exportsMap[exportName]
        if (location == null) {
            if (log.isDebugEnabled) {
                log.debug("npm export has no location: package=$packageName export=$exportName")
            }
            return null
        }
        val resolved = resolveLocation(project, location) ?: return null
        return arrayOf(resolved)
    }

    fun getAnyMemberPsiElements(project: Project, memberName: String, preferredReceiverType: String? = null): Array<PsiElement>? {
        if (log.isDebugEnabled) {
            log.debug("Searching for member '$memberName' across ${interfaces.size} interfaces")
        }
        data class Hit(val element: PsiElement, val ifaceName: String)
        val hits = mutableListOf<Hit>()
        var foundCount = 0

        for ((ifaceName, iface) in interfaces) {
            val locations = iface.members[memberName]?.mapNotNull { it.location } ?: continue
            for (location in locations) {
                foundCount++
                val resolved = resolveLocation(project, location)
                if (resolved != null) {
                    hits.add(Hit(resolved, ifaceName))
                }
            }
        }

        if (hits.isEmpty()) return null

        val sorted = if (preferredReceiverType != null) {
            hits.sortedWith(
                compareBy(
                    {
                        when {
                            it.ifaceName == preferredReceiverType -> 0
                            it.ifaceName.endsWith(preferredReceiverType) -> 1
                            else -> 2
                        }
                    },
                    { it.ifaceName }
                )
            )
        } else {
            hits.sortedBy { it.ifaceName }
        }

        if (log.isDebugEnabled) {
            log.debug("Member '$memberName': scanned $foundCount locations, resolved ${sorted.size} PSI elements")
        }
        return sorted.map { hit ->
            val m = interfaces[hit.ifaceName]?.members?.get(memberName)?.firstOrNull()
            wrapIndexedMember(project, hit.element, hit.ifaceName, m)
        }.toTypedArray()
    }

    private fun wrapIndexedMember(project: Project, resolved: PsiElement, declaringInterface: String, member: JsMember?): PsiElement {
        val deprecated = member?.doc?.contains("@deprecated", ignoreCase = true) == true
        val mgr = PsiManager.getInstance(project)
        return JsMemberNavigationTarget(mgr, resolved.language, resolved, declaringInterface, deprecated)
    }

    private fun resolveLocation(project: Project, location: JsLocation): PsiElement? {
        val virtualFile = BundledDomLibs.resolveVirtualFile(location.filePath)
            ?: LocalFileSystem.getInstance().findFileByPath(location.filePath)

        if (virtualFile == null) {
            log.warn("Could not find file for JS location (check path is absolute and exists): ${location.filePath}")
            return null
        }

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        if (psiFile.textLength == 0) return null
        var offset = location.offset.coerceIn(0, psiFile.textLength - 1)
        var element = psiFile.findElementAt(offset)
        var guard = 0
        while (element != null && guard++ < 32 &&
            (element is com.intellij.psi.PsiWhiteSpace || element is com.intellij.psi.PsiComment)) {
            offset = element.textRange.endOffset.coerceAtMost(psiFile.textLength - 1)
            element = psiFile.findElementAt(offset)
        }

        if (element == null) {
            log.warn("findElementAt(${location.offset}) returned null in ${virtualFile.path}")
        }

        return element
    }

    // ─── Query API ──────────────────────────────────────────────────────────

    fun isKnownGlobal(name: String): Boolean = globals.containsKey(name) || functions.containsKey(name)
    fun resolveGlobalInfo(name: String): JsVariableInfo? = globals[name]
    fun resolveFunctions(name: String): List<JsMember>? = functions[name]
    fun isKnownNpmPackage(packageName: String): Boolean = npmExports.containsKey(packageName)
    fun isKnownNpmExport(packageName: String, symbolName: String): Boolean = npmExports[packageName]?.containsKey(symbolName) == true
    fun resolveGlobalType(name: String): String? = globals[name]?.type
    fun resolveInterface(typeName: String): JsInterface? = interfaces[typeName]
    fun resolveMember(globalName: String, memberName: String): List<JsMember>? {
        val typeName = globals[globalName]?.type ?: return null
        return interfaces[typeName]?.members?.get(memberName)
    }

    /**
     * Resolves a dotted `js/` chain to the resulting TypeScript type name after walking
     * globals, properties, and method return types (first overload).
     */
    fun resolveJsChainType(segments: List<String>): String? {
        if (segments.isEmpty()) return null
        var type = resolveGlobalType(segments[0])
            ?: if (resolveFunctions(segments[0]) != null) "Function" else null
            ?: return null
        for (i in 1 until segments.size) {
            val memberName = segments[i]
            val iface = resolveInterface(type) ?: return null
            val member = iface.members[memberName]?.firstOrNull() ?: return null
            type = when (member.kind) {
                "method" -> member.returns
                else -> member.type
            }
        }
        return type
    }

    fun allGlobalNames(): Collection<String> = globals.keys
    fun allFunctionNames(): Collection<String> = functions.keys
    fun npmExportNames(packageName: String): Collection<String> = npmExports[packageName]?.keys ?: emptySet()
    fun allInterfaces(): Map<String, JsInterface> = interfaces
    fun hasMemberName(memberName: String): Boolean = memberSamples.containsKey(memberName)
    val globalCount: Int get() = globals.size
    val interfaceCount: Int get() = interfaces.size

    companion object {
        @JvmStatic fun getInstance(): JsSymbolIndex = service()
    }
}