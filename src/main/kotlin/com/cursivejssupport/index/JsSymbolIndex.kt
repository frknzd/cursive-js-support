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

data class JsResolvedMember(
    val declaringType: String,
    val memberName: String,
    val overloads: List<JsMember>,
    val distance: Int,
) {
    val first: JsMember? get() = overloads.firstOrNull()
}

@Service(Service.Level.APP)
class JsSymbolIndex {

    private val log = logger<JsSymbolIndex>()

    private val globals = ConcurrentHashMap<String, JsVariableInfo>()
    private val interfaces = ConcurrentHashMap<String, JsInterface>()
    private val functions = ConcurrentHashMap<String, List<JsMember>>()

    // CHANGED: Now maps PackageName -> (ExportName -> Location)
    private val npmExports = ConcurrentHashMap<String, MutableMap<String, JsLocation?>>()
    /** Export name → TypeScript type name (from parsed .d.ts) for npm member completion. */
    private val npmExportTypes = ConcurrentHashMap<String, MutableMap<String, String>>()

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
                    extends = (existing.extends + incoming.extends).distinct(),
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
                        extends = (existing.extends + incoming.extends).distinct(),
                        members = merged
                    )
                }
            }
            rebuildMemberSamples()
        }

        val exports = mutableMapOf<String, JsLocation?>()
        val exportTypes = npmExportTypes.computeIfAbsent(packageName) { ConcurrentHashMap() }

        // Extract locations for all exports
        symbols.variables.forEach { (name, info) ->
            exports[name] = info.location
            exportTypes[name] = info.type
        }
        symbols.functions.forEach { (name, overloads) ->
            exports[name] = overloads.firstOrNull()?.location
            val m = overloads.firstOrNull()
            exportTypes[name] = m?.returns?.takeIf { it.isNotBlank() } ?: "Function"
        }

        if (exports.isNotEmpty()) {
            npmExports[packageName] = exports
        }
    }

    private fun rebuildMemberSamples() {
        memberSamples.clear()
        for (typeName in interfaces.keys) {
            for ((memberName, resolved) in resolveMembers(typeName)) {
                val overloads = resolved.overloads
                val first = overloads.firstOrNull() ?: continue
                val bucket = memberSamples.computeIfAbsent(memberName) { mutableListOf() }
                if (bucket.size < 8) {
                    bucket.add(resolved.declaringType to first)
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
        val resolvedMember = resolveMember(typeName, memberName) ?: return null
        val out = resolvedMember.overloads.mapNotNull { m ->
            val loc = m.location ?: return@mapNotNull null
            val resolved = resolveLocation(project, loc) ?: return@mapNotNull null
            wrapIndexedMember(project, resolved, resolvedMember.declaringType, m, loc)
        }
        return if (out.isNotEmpty()) out.toTypedArray() else null
    }

    fun getNpmExportPsiElements(project: Project, packageName: String, exportName: String): Array<PsiElement>? {
        val exportsMap = npmExports[packageName] ?: return null
        
        if (exportsMap.containsKey(exportName)) {
            val location = exportsMap[exportName] ?: return null
            val resolved = resolveLocation(project, location) ?: return null
            return arrayOf(resolved)
        }

        // Fallback: If not an explicit export, it might be a global/class defined in the package's typings
        // (common with 'export as namespace' or simple top-level declarations)
        val locations = mutableListOf<JsLocation>()
        globals[exportName]?.location?.let { locations.add(it) }
        functions[exportName]?.forEach { it.location?.let { loc -> locations.add(loc) } }
        
        val elements = locations.mapNotNull { resolveLocation(project, it) }
        return if (elements.isNotEmpty()) elements.toTypedArray() else null
    }

    fun getAnyMemberPsiElements(project: Project, memberName: String, preferredReceiverType: String? = null): Array<PsiElement>? {
        val candidates = collectMemberCandidates(memberName)
        if (candidates.isEmpty()) return null

        val deduped = dedupeMemberCandidatesByLocation(candidates)
        val sorted = sortMemberCandidates(deduped, preferredReceiverType)

        val out = sorted.mapNotNull { c ->
            val resolved = resolveLocation(project, c.location) ?: return@mapNotNull null
            wrapIndexedMember(project, resolved, c.declaringInterface, c.member, c.location)
        }
        return if (out.isNotEmpty()) out.toTypedArray() else null
    }

    /**
     * Internal data class for member-resolution candidates. Exposed for unit tests that exercise
     * the dedup grouping without standing up a Project / PSI.
     */
    internal data class MemberCandidate(
        val declaringInterface: String,
        val distance: Int,
        val member: JsMember,
        val location: JsLocation,
    )

    internal fun collectMemberCandidates(memberName: String): List<MemberCandidate> {
        val out = ArrayList<MemberCandidate>()
        for (ifaceName in interfaces.keys) {
            val resolvedMember = resolveMember(ifaceName, memberName) ?: continue
            for (overload in resolvedMember.overloads) {
                val loc = overload.location ?: continue
                out.add(
                    MemberCandidate(
                        declaringInterface = resolvedMember.declaringType,
                        distance = resolvedMember.distance,
                        member = overload,
                        location = loc,
                    )
                )
            }
        }
        return out
    }

    /**
     * Collapses candidates that point at the exact same `(file, offset)`. When several base
     * interfaces expose the same `.d.ts` location through inheritance we keep the entry with the
     * smallest distance (the most-specific declaring interface).
     */
    internal fun dedupeMemberCandidatesByLocation(candidates: List<MemberCandidate>): List<MemberCandidate> =
        candidates
            .groupBy { it.location.filePath to it.location.offset }
            .map { (_, group) -> group.minBy { it.distance } }

    internal fun sortMemberCandidates(
        candidates: List<MemberCandidate>,
        preferredReceiverType: String?,
    ): List<MemberCandidate> =
        if (preferredReceiverType != null) {
            candidates.sortedWith(
                compareBy(
                    {
                        when {
                            it.declaringInterface == preferredReceiverType -> 0
                            interfaces[preferredReceiverType]?.extends?.contains(it.declaringInterface) == true -> 1
                            else -> 2 + it.distance
                        }
                    },
                    { it.distance },
                    { it.declaringInterface }
                )
            )
        } else {
            candidates.sortedWith(compareBy({ it.distance }, { it.declaringInterface }))
        }

    private fun wrapIndexedMember(
        project: Project,
        resolved: PsiElement,
        declaringInterface: String,
        member: JsMember?,
        location: JsLocation?,
    ): PsiElement {
        val deprecated = member?.doc?.contains("@deprecated", ignoreCase = true) == true
        val mgr = PsiManager.getInstance(project)
        return JsMemberNavigationTarget(mgr, resolved.language, resolved, declaringInterface, deprecated, location, member)
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

    fun resolveMembers(typeName: String): Map<String, JsResolvedMember> {
        val out = linkedMapOf<String, JsResolvedMember>()
        collectMembers(typeName, distance = 0, seen = mutableSetOf(), out = out)
        return out
    }

    fun resolveMember(typeName: String, memberName: String): JsResolvedMember? =
        resolveMembers(typeName)[memberName]

    private fun collectMembers(
        typeName: String,
        distance: Int,
        seen: MutableSet<String>,
        out: MutableMap<String, JsResolvedMember>,
    ) {
        if (!seen.add(typeName)) return
        val iface = interfaces[typeName] ?: return
        for ((memberName, overloads) in iface.members) {
            out.putIfAbsent(
                memberName,
                JsResolvedMember(
                    declaringType = typeName,
                    memberName = memberName,
                    overloads = overloads,
                    distance = distance,
                ),
            )
        }
        for (base in iface.extends) {
            collectMembers(base, distance + 1, seen, out)
        }
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
            val member = resolveMember(type, memberName)?.first ?: return null
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

    /** TypeScript type for an npm export (e.g. `default` → `React.ComponentType`), if known from typings. */
    fun resolveNpmExportType(packageName: String, exportName: String): String? =
        npmExportTypes[packageName]?.get(exportName)
    fun hasMemberName(memberName: String): Boolean = memberSamples.containsKey(memberName)

    companion object {
        @JvmStatic fun getInstance(): JsSymbolIndex = service()
    }
}