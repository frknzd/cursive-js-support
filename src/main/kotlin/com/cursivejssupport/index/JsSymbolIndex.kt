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
import java.util.concurrent.atomic.AtomicReference
import java.io.File

data class JsResolvedMember(
    val declaringType: String,
    val memberName: String,
    val overloads: List<JsMember>,
    val distance: Int,
) {
    val first: JsMember? get() = overloads.firstOrNull()
}

@Service(Service.Level.PROJECT)
class JsSymbolIndex {

    private val log = logger<JsSymbolIndex>()

    private class Builder {
        val globals = ConcurrentHashMap<String, JsVariableInfo>()
        val interfaces = ConcurrentHashMap<String, JsInterface>()
        val functions = ConcurrentHashMap<String, List<JsMember>>()
        val npmExports = ConcurrentHashMap<String, MutableMap<String, JsLocation?>>()
        val npmExportTypes = ConcurrentHashMap<String, MutableMap<String, String>>()
        val memberSamples = ConcurrentHashMap<String, MutableList<Pair<String, JsMember>>>()
        val aliases = ConcurrentHashMap<String, String>()
        val npmExportMembers = ConcurrentHashMap<String, MutableMap<String, List<JsMember>>>()
        val npmExportDocs = ConcurrentHashMap<String, MutableMap<String, String>>()
    }

    private data class Snapshot(
        val globals: Map<String, JsVariableInfo> = emptyMap(),
        val interfaces: Map<String, JsInterface> = emptyMap(),
        val functions: Map<String, List<JsMember>> = emptyMap(),
        val npmExports: Map<String, Map<String, JsLocation?>> = emptyMap(),
        val npmExportTypes: Map<String, Map<String, String>> = emptyMap(),
        val memberSamples: Map<String, List<Pair<String, JsMember>>> = emptyMap(),
        val aliases: Map<String, String> = emptyMap(),
        val npmExportMembers: Map<String, Map<String, List<JsMember>>> = emptyMap(),
        val npmExportDocs: Map<String, Map<String, String>> = emptyMap(),
    ) {
        val typeGraph = JsTypeGraph(interfaces, aliases, { globals[it]?.type }, functions::containsKey)
        val resolvedMembers = ConcurrentHashMap<String, Map<String, JsResolvedMember>>()
        val constructorSignatures: Map<String, List<JsMember>> = buildMap {
            for ((name, info) in globals) {
                val declared = JsTypeRef.parse(info.type)
                val signatures = typeGraph.constructSignatures(declared).ifEmpty {
                    typeGraph.constructSignatures(JsTypeRef.parse(typeGraph.canonical(info.type)))
                }
                if (signatures.isNotEmpty()) put(name, signatures)
            }
        }
        val npmPackageNames: Set<String> = npmExports.keys
            .filterTo(linkedSetOf()) { it != "goog" && !it.startsWith("goog.") }
        val googNamespaceNames: List<String> = npmExports.keys
            .filter { it == "goog" || it.startsWith("goog.") }
            .sorted()
    }

    private val builder = Builder()
    private val builderTypeGraph = JsTypeGraph(
        builder.interfaces,
        builder.aliases,
        { builder.globals[it]?.type },
        builder.functions::containsKey,
    )
    @Volatile private var memberSamplesDirty = true
    private val snapshot = AtomicReference(Snapshot())
    private val published get() = _loaded.get()
    private val globals get() = if (published) snapshot.get().globals else builder.globals
    private val interfaces get() = if (published) snapshot.get().interfaces else builder.interfaces
    private val functions get() = if (published) snapshot.get().functions else builder.functions

    // CHANGED: Now maps PackageName -> (ExportName -> Location)
    private val npmExports get() = if (published) snapshot.get().npmExports else builder.npmExports
    /** Export name → TypeScript type name (from parsed .d.ts) for npm member completion. */
    private val npmExportTypes get() = if (published) snapshot.get().npmExportTypes else builder.npmExportTypes

    /** Union/intersection type aliases (`BodyInit` → `"Blob|BufferSource|…"`) from the extractor. */
    private val aliases get() = if (published) snapshot.get().aliases else builder.aliases

    /** Package → export name → function overloads (kept for hover; goog namespaces load as packages too). */
    private val npmExportMembers get() = if (published) snapshot.get().npmExportMembers else builder.npmExportMembers

    /** Package → export name → JSDoc of the export's declaration. */
    private val npmExportDocs get() = if (published) snapshot.get().npmExportDocs else builder.npmExportDocs

    private val _loaded = AtomicBoolean(false)
    private val _loading = AtomicBoolean(false)
    val isLoaded: Boolean get() = _loaded.get()

    fun claimLoad(): Boolean = _loading.compareAndSet(false, true)
    fun setLoaded(value: Boolean) {
        if (value && !_loaded.get()) snapshot.set(freeze())
        _loaded.set(value)
    }
    fun finishLoadFailure() { _loading.set(false) }

    /** Publish a completely-built index in one volatile write. */
    fun publish(replacement: JsSymbolIndex) {
        snapshot.set(replacement.freeze())
        _loaded.set(true)
        _loading.set(false)
    }

    private fun freeze(): Snapshot {
        ensureMemberSamples()
        return Snapshot(
            builder.globals.toMap(), builder.interfaces.toMap(), builder.functions.toMap(),
            builder.npmExports.mapValues { it.value.toMap() },
            builder.npmExportTypes.mapValues { it.value.toMap() },
            builder.memberSamples.mapValues { it.value.toList() }, builder.aliases.toMap(),
            builder.npmExportMembers.mapValues { it.value.toMap() },
            builder.npmExportDocs.mapValues { it.value.toMap() },
        )
    }

    fun load(symbols: ParsedSymbols) {
        for ((name, iface) in symbols.interfaces) {
            builder.interfaces.merge(name, iface) { existing, incoming ->
                val merged = existing.members.toMutableMap()
                for ((memberName, overloads) in incoming.members) {
                    merged.merge(memberName, overloads) { a, b -> a + b }
                }
                JsInterface(
                    location = existing.location ?: incoming.location,
                    extends = (existing.extends + incoming.extends).distinct(),
                    members = merged,
                    typeParams = existing.typeParams.ifEmpty { incoming.typeParams },
                    environment = existing.environment ?: incoming.environment,
                )
            }
        }
        if (symbols.interfaces.isNotEmpty()) memberSamplesDirty = true
        for ((name, info) in symbols.variables) builder.globals[name] = info
        for ((name, overloads) in symbols.functions) builder.functions.merge(name, overloads) { a, b -> a + b }
        for ((name, target) in symbols.aliases) builder.aliases.putIfAbsent(name, target)
        if (symbols.aliases.isNotEmpty()) memberSamplesDirty = true
        // Ambient external modules (declare module "fs" {...}) are registered as packages so
        // `(:require ["fs" :as fs])` resolves against the bundled Node/Bun/Deno symbol sets.
        for ((moduleName, moduleSymbols) in symbols.modules) {
            loadNpmPackage(moduleName, moduleSymbols, flattenExportAssignment = true)
        }
        if (published) snapshot.set(freeze())
    }

    /**
     * [flattenExportAssignment] is set for the bundled ambient modules only — see
     * [addExportAssignmentMembers]. Packages resolved from `node_modules` keep the exports the
     * TypeScript compiler reports for them, which `NpmInteropCorpusTest` checks against a
     * reference report generated by TypeScript itself.
     */
    fun loadNpmPackage(packageName: String, symbols: ParsedSymbols, flattenExportAssignment: Boolean = false) {
        if (symbols.interfaces.isNotEmpty()) {
            for ((name, iface) in symbols.interfaces) {
                builder.interfaces.merge(name, iface) { existing, incoming ->
                    val merged = existing.members.toMutableMap()
                    for ((memberName, overloads) in incoming.members) {
                        merged.merge(memberName, overloads) { a, b -> a + b }
                    }
                    JsInterface(
                        location = existing.location ?: incoming.location,
                        extends = (existing.extends + incoming.extends).distinct(),
                        members = merged,
                        typeParams = existing.typeParams.ifEmpty { incoming.typeParams },
                        environment = existing.environment ?: incoming.environment,
                    )
                }
            }
            memberSamplesDirty = true
        }
        for ((name, target) in symbols.aliases) builder.aliases.putIfAbsent(name, target)
        if (symbols.aliases.isNotEmpty()) memberSamplesDirty = true

        val exports = mutableMapOf<String, JsLocation?>()
        val exportTypes = builder.npmExportTypes.computeIfAbsent(packageName) { ConcurrentHashMap() }
        val exportMembers = builder.npmExportMembers.computeIfAbsent(packageName) { ConcurrentHashMap() }
        val exportDocs = builder.npmExportDocs.computeIfAbsent(packageName) { ConcurrentHashMap() }

        // Extract locations for all exports
        symbols.variables.filterKeys { symbols.moduleExports == null || it in symbols.moduleExports }.forEach { (name, info) ->
            exports[name] = info.location
            exportTypes[name] = info.type
            info.doc?.let { exportDocs[name] = it }
        }
        symbols.functions.filterKeys { symbols.moduleExports == null || it in symbols.moduleExports }.forEach { (name, overloads) ->
            exports[name] = overloads.firstOrNull()?.location
            val m = overloads.firstOrNull()
            exportTypes[name] = symbols.variables[name]?.type?.takeIf { it.isNotBlank() }
                ?: m?.returns?.takeIf { it.isNotBlank() }
                ?: "Function"
            exportMembers[name] = overloads
            m?.doc?.let { exportDocs[name] = it }
        }

        if (flattenExportAssignment) {
            addExportAssignmentMembers(symbols, exports, exportTypes, exportMembers, exportDocs)
        }

        if (exports.isNotEmpty()) {
            builder.npmExports[packageName] = exports
        }
        if (published) snapshot.set(freeze())
    }

    /**
     * Flatten the members of an export-assigned module object into the package's named exports.
     *
     * `@types/node` declares `path` as `const path: path.PlatformPath; export = path` — the module
     * has no named exports at all, its whole API lives on the type of one object. Both the CLJS
     * and the ES import forms treat those members as the module's names (`path/join`,
     * `(:require ["path" :refer [join]])`), so the index has to as well or `path/join` reads as
     * "package 'path' has no export 'join'". The same shape covers CommonJS packages whose typings
     * end in `export = someObject`.
     *
     * Real named exports win: this only fills in names the module didn't already declare, and
     * construct-signature/class plumbing is never an export.
     */
    private fun addExportAssignmentMembers(
        symbols: ParsedSymbols,
        exports: MutableMap<String, JsLocation?>,
        exportTypes: MutableMap<String, String>,
        exportMembers: MutableMap<String, List<JsMember>>,
        exportDocs: MutableMap<String, String>,
    ) {
        // The default export's declared type, resolved one hop through a variable of the same name
        // (`default: "path"` → `path: "PlatformPath"`) the way the extractor emits `export =`.
        val defaultType = symbols.variables["default"]?.type?.takeIf { it.isNotBlank() } ?: return
        val objectType = symbols.variables[defaultType]?.type?.takeIf { it.isNotBlank() } ?: defaultType
        val members = resolveMembers(objectType).takeIf { it.isNotEmpty() } ?: return

        for ((name, resolved) in members) {
            if (name in exports || name in NON_RUNTIME_MEMBERS) continue
            val first = resolved.overloads.firstOrNull() ?: continue
            exports[name] = first.location
            exportTypes[name] = memberValueTypeName(first).ifEmpty { "Function" }
            exportMembers[name] = resolved.overloads
            first.doc?.let { exportDocs[name] = it }
        }
    }

    private fun ensureMemberSamples() {
        if (!memberSamplesDirty) return
        synchronized(builder.memberSamples) {
            if (!memberSamplesDirty) return
            builder.memberSamples.clear()
            for (typeName in builder.interfaces.keys) {
                for ((memberName, resolved) in builderTypeGraph.members(typeName)) {
                    val first = resolved.overloads.firstOrNull() ?: continue
                    val bucket = builder.memberSamples.computeIfAbsent(memberName) { mutableListOf() }
                    if (bucket.size < 8) {
                        bucket.add(resolved.declaringType to first)
                    }
                }
            }
            memberSamplesDirty = false
        }
    }

    private fun currentMemberSamples(): Map<String, List<Pair<String, JsMember>>> {
        if (published) return snapshot.get().memberSamples
        ensureMemberSamples()
        return builder.memberSamples
    }

    /**
     * For completion when receiver type is unknown: members whose name starts with [namePrefix], capped.
     */
    fun sampleMembersByNamePrefix(namePrefix: String, limit: Int = 220): List<Triple<String, String, JsMember>> {
        val memberSamples = currentMemberSamples()
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
        val exportsMap = npmExportsFor(packageName) ?: return null
        
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

        val resolved = sorted.mapNotNull { c ->
            val el = resolveLocation(project, c.location) ?: return@mapNotNull null
            c to el
        }
        if (resolved.isEmpty()) return null
        // These are alternatives (the receiver type was unknown or unconfirmed) — number the
        // rows so the chooser reads "name in Interface (i/N)". A single survivor needs no label.
        val total = resolved.size
        val out = resolved.mapIndexed { i, (c, el) ->
            wrapIndexedMember(
                project, el, c.declaringInterface, c.member, c.location,
                ordinal = if (total > 1) i + 1 else null,
                total = if (total > 1) total else null,
            )
        }
        return out.toTypedArray()
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
     * Every interface that DIRECTLY declares [memberName], with its full overload list. The
     * doc-oriented twin of [collectMemberCandidates]: members without a PSI location still
     * document, and there is no inheritance walk — an inherited hit collapses to its direct
     * declarer anyway, so a single map lookup per interface suffices. Distance is always 0.
     */
    fun memberDeclarations(memberName: String): List<JsResolvedMember> {
        val memberSamples = currentMemberSamples()
        // memberSamples buckets are capped, but the key set is complete — a cheap miss check.
        if (!memberSamples.containsKey(memberName)) return emptyList()
        return interfaces.entries.mapNotNull { (typeName, iface) ->
            iface.members[memberName]?.let { overloads ->
                JsResolvedMember(
                    declaringType = typeName,
                    memberName = memberName,
                    overloads = overloads,
                    distance = 0,
                )
            }
        }
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
        ordinal: Int? = null,
        total: Int? = null,
    ): PsiElement {
        val deprecated = member?.doc?.contains("@deprecated", ignoreCase = true) == true
        val mgr = PsiManager.getInstance(project)
        return JsMemberNavigationTarget(
            mgr, resolved.language, resolved, declaringInterface, deprecated, location, member,
            ordinal = ordinal, total = total,
        )
    }

    private fun resolveLocation(project: Project, location: JsLocation): PsiElement? {
        val virtualFile = BundledGoogLibs.resolveVirtualFile(location.filePath)
            ?: BundledDomLibs.resolveVirtualFile(location.filePath)
            ?: LocalFileSystem.getInstance().findFileByPath(location.filePath)
            ?: run {
                val base = project.basePath ?: return@run null
                LocalFileSystem.getInstance().findFileByPath("$base/${location.filePath}")
            }

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
    /**
     * `new` overloads declared by a global's companion type. Core ECMAScript spells those
     * companions out as named interfaces (`declare var Error: ErrorConstructor`), while the DOM
     * libs use anonymous object literals the extractor names `TYPE$X` — both reach the same
     * `new` members through the type graph, so the shape of the declaration doesn't matter.
     */
    fun globalConstructSignatures(name: String): List<JsMember> {
        if (published) return snapshot.get().constructorSignatures[name].orEmpty()
        val declared = globals[name]?.type ?: return emptyList()
        return resolveConstructSignatures(JsTypeRef.parse(declared))
            .ifEmpty { resolveConstructSignatures(JsTypeRef.parse(canonicalType(declared))) }
    }

    fun isConstructorGlobal(name: String): Boolean = globalConstructSignatures(name).isNotEmpty()
    fun isKnownNpmPackage(packageName: String): Boolean = npmExports.containsKey(packageName)
    fun isKnownNpmExport(packageName: String, symbolName: String): Boolean = npmExportsFor(packageName)?.containsKey(symbolName) == true
    fun resolveGlobalType(name: String): String? = globals[name]?.type
    fun resolveInterface(typeName: String): JsInterface? = interfaces[typeName]

    private fun typeGraph(): JsTypeGraph = if (published) snapshot.get().typeGraph else builderTypeGraph

    fun resolveMembers(typeName: String): Map<String, JsResolvedMember> {
        if (!published) return builderTypeGraph.members(typeName)
        val current = snapshot.get()
        return current.resolvedMembers.computeIfAbsent(typeName, current.typeGraph::members)
    }

    fun resolveMember(typeName: String, memberName: String): JsResolvedMember? =
        resolveMembers(typeName)[memberName]

    fun resolveJsChainType(segments: List<String>): String? =
        resolveJsChainTypeRef(segments)?.primaryName()?.takeIf { it.isNotEmpty() }

    fun resolveJsChainTypeRef(segments: List<String>): JsTypeRef? = typeGraph().chain(segments)
    fun canonicalType(rawType: String): String = typeGraph().canonical(rawType)
    fun memberValueType(member: JsMember): JsTypeRef = typeGraph().memberType(member)
    fun memberValueTypeName(member: JsMember): String = memberValueType(member).primaryName()
    fun resolveMembersOf(type: JsTypeRef): Map<String, JsResolvedMember> = typeGraph().membersOf(type)
    fun substitutionFor(type: JsTypeRef): Map<String, JsTypeRef> = typeGraph().substitution(type)
    fun substitute(ref: JsTypeRef, substitution: Map<String, JsTypeRef>): JsTypeRef = typeGraph().substitute(ref, substitution)
    fun resolveCallSignatures(type: JsTypeRef): List<JsMember> = typeGraph().callSignatures(type)
    fun resolveConstructSignatures(type: JsTypeRef): List<JsMember> = typeGraph().constructSignatures(type)
    fun resolveIndexedValueType(type: JsTypeRef, numeric: Boolean): JsTypeRef? = typeGraph().indexedValue(type, numeric)

    fun allGlobalNames(): Collection<String> = globals.keys
    fun allFunctionNames(): Collection<String> = functions.keys

    /**
     * Look up a package's exports map, trying the name as-is and then the `node:`-stripped form
     * so `node:fs` and `fs` resolve to the same bundled ambient module.
     */
    private fun npmExportsFor(packageName: String): Map<String, JsLocation?>? =
        npmExports[packageName] ?: npmExports[packageName.removePrefix("node:")]

    fun npmExportNames(packageName: String): Collection<String> = npmExportsFor(packageName)?.keys ?: emptySet()
    fun npmPackageNames(): Set<String> = if (published) snapshot.get().npmPackageNames else npmExports.keys
        .filterTo(linkedSetOf()) { it != "goog" && !it.startsWith("goog.") }
    fun indexedNpmPackageCount(): Int = npmExports.keys.count { it != "goog" && !it.startsWith("goog.") }

    /** TypeScript type for an npm export (e.g. `default` → `React.ComponentType`), if known from typings. */
    fun resolveNpmExportType(packageName: String, exportName: String): String? =
        npmExportTypes[packageName]?.get(exportName) ?: npmExportTypes[packageName.removePrefix("node:")]?.get(exportName)

    /** Function overloads for a function-shaped npm/goog export, if the typings carried them. */
    fun resolveNpmExportMembers(packageName: String, exportName: String): List<JsMember>? =
        npmExportMembers[packageName]?.get(exportName) ?: npmExportMembers[packageName.removePrefix("node:")]?.get(exportName)

    /** JSDoc attached to an npm/goog export's declaration. */
    fun resolveNpmExportDoc(packageName: String, exportName: String): String? =
        npmExportDocs[packageName]?.get(exportName) ?: npmExportDocs[packageName.removePrefix("node:")]?.get(exportName)
    fun hasMemberName(memberName: String): Boolean = currentMemberSamples().containsKey(memberName)

    fun getGoogNamespaceNames(): List<String> = if (published) snapshot.get().googNamespaceNames
        else npmExports.keys.filter { it == "goog" || it.startsWith("goog.") }.sorted()

    fun isKnownGoogNamespace(name: String): Boolean =
        (name == "goog" || name.startsWith("goog.")) && npmExports.containsKey(name)

    companion object {
        @JvmStatic fun getInstance(project: Project): JsSymbolIndex = project.service()

        /** Class plumbing a type carries but a module never exports under that name. */
        private val NON_RUNTIME_MEMBERS = setOf("new", "prototype", "constructor")
    }
}
