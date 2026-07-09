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

    /** Union/intersection type aliases (`BodyInit` → `"Blob|BufferSource|…"`) from the extractor. */
    private val aliases = ConcurrentHashMap<String, String>()

    /** Package → export name → function overloads (kept for hover; goog namespaces load as packages too). */
    private val npmExportMembers = ConcurrentHashMap<String, MutableMap<String, List<JsMember>>>()

    /** Package → export name → JSDoc of the export's declaration. */
    private val npmExportDocs = ConcurrentHashMap<String, MutableMap<String, String>>()

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
        for ((name, target) in symbols.aliases) aliases.putIfAbsent(name, target)
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
        for ((name, target) in symbols.aliases) aliases.putIfAbsent(name, target)

        val exports = mutableMapOf<String, JsLocation?>()
        val exportTypes = npmExportTypes.computeIfAbsent(packageName) { ConcurrentHashMap() }
        val exportMembers = npmExportMembers.computeIfAbsent(packageName) { ConcurrentHashMap() }
        val exportDocs = npmExportDocs.computeIfAbsent(packageName) { ConcurrentHashMap() }

        // Extract locations for all exports
        symbols.variables.forEach { (name, info) ->
            exports[name] = info.location
            exportTypes[name] = info.type
            info.doc?.let { exportDocs[name] = it }
        }
        symbols.functions.forEach { (name, overloads) ->
            exports[name] = overloads.firstOrNull()?.location
            val m = overloads.firstOrNull()
            exportTypes[name] = m?.returns?.takeIf { it.isNotBlank() } ?: "Function"
            exportMembers[name] = overloads
            m?.doc?.let { exportDocs[name] = it }
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
    fun isConstructorGlobal(name: String): Boolean =
        globals[name]?.type?.startsWith("TYPE\$") == true
    fun isKnownNpmPackage(packageName: String): Boolean = npmExports.containsKey(packageName)
    fun isKnownNpmExport(packageName: String, symbolName: String): Boolean = npmExports[packageName]?.containsKey(symbolName) == true
    fun resolveGlobalType(name: String): String? = globals[name]?.type
    fun resolveInterface(typeName: String): JsInterface? = interfaces[typeName]

    fun resolveMembers(typeName: String): Map<String, JsResolvedMember> {
        val out = linkedMapOf<String, JsResolvedMember>()
        collectMembers(canonicalType(typeName), distance = 0, seen = mutableSetOf(), out = out)
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
            collectMembers(canonicalType(base), distance + 1, seen, out)
        }
    }

    /**
     * Resolves a dotted `js/` chain to the resulting TypeScript type name after walking
     * globals, properties, and method return types (first overload). Canonical-name twin of
     * [resolveJsChainTypeRef].
     */
    fun resolveJsChainType(segments: List<String>): String? =
        resolveJsChainTypeRef(segments)?.primaryName()?.takeIf { it.isNotEmpty() }

    /**
     * Generic-aware `js/` chain walk: `js/document.querySelectorAll` resolves to
     * `NodeListOf<Element>` with its type arguments intact, substituting interface type
     * parameters (`NodeListOf<T>.item(): T` → `Element`) along the way.
     */
    fun resolveJsChainTypeRef(segments: List<String>): JsTypeRef? {
        if (segments.isEmpty()) return null
        var type: JsTypeRef = resolveGlobalType(segments[0])?.let { expandAliases(JsTypeRef.parse(it)) }
            ?: (if (resolveFunctions(segments[0]) != null) JsTypeRef.Named("Function") else null)
            ?: return null
        for (i in 1 until segments.size) {
            val member = resolveMembersOf(type)[segments[i]]?.first ?: return null
            type = substitute(memberValueType(member), substitutionFor(type))
        }
        return type
    }

    /**
     * The single interface name to resolve members against for a raw type string.
     *
     * Structured via [JsTypeRef]: union/intersection parts are preferred concrete-interface
     * first (`"absolute"|CSSPositionValue` → the interface, `string|null` → `string`), generic
     * args are stripped (`Promise<Response>` → `Promise`), arrays map to `Array` (fixing the
     * previous `Element[]` dead end), and union/intersection type aliases (`BodyInit`) expand
     * to their branches first.
     */
    fun canonicalType(rawType: String): String {
        // Fast path: a plain name that isn't an alias maps to itself.
        if (rawType.none { it in "|&<[(" } && !aliases.containsKey(rawType)) return rawType
        val name = expandAliases(JsTypeRef.parse(rawType)).primaryName()
        return name.ifEmpty { rawType }
    }

    /**
     * The single member-type projection: a method's return type or a property's type, with
     * aliases expanded. Every chain walk / display site funnels through here.
     */
    fun memberValueType(member: JsMember): JsTypeRef =
        expandAliases(JsTypeRef.parse(if (member.kind == "method") member.returns else member.type))

    /** Canonical-name twin of [memberValueType]. */
    fun memberValueTypeName(member: JsMember): String =
        memberValueType(member).primaryName()

    /**
     * Generic-aware member resolution: members of the type's preferred interface
     * ([JsTypeRef.primaryNamed]), falling back to other union branches when the preferred
     * one is unknown to the index.
     */
    fun resolveMembersOf(type: JsTypeRef): Map<String, JsResolvedMember> {
        val named = type.primaryNamed() ?: return emptyMap()
        val direct = resolveMembers(named.name)
        if (direct.isNotEmpty() || type !is JsTypeRef.Union) return direct
        for (branch in type.leafNameds()) {
            if (branch.name == named.name) continue
            val r = resolveMembers(branch.name)
            if (r.isNotEmpty()) return r
        }
        return direct
    }

    /**
     * Type-parameter bindings of a generic instantiation: `NodeListOf<HTMLDivElement>` →
     * `{T → HTMLDivElement}`. Empty when the type carries no args or the interface declares
     * no type parameters (legacy indexes).
     */
    fun substitutionFor(type: JsTypeRef): Map<String, JsTypeRef> {
        val named = type.primaryNamed() ?: return emptyMap()
        if (named.args.isEmpty()) return emptyMap()
        val params = interfaces[named.name]?.typeParams ?: return emptyMap()
        if (params.isEmpty()) return emptyMap()
        return params.zip(named.args).toMap()
    }

    /** Applies a [substitutionFor] map to free type parameters inside [ref]. */
    fun substitute(ref: JsTypeRef, substitution: Map<String, JsTypeRef>): JsTypeRef {
        if (substitution.isEmpty()) return ref
        return when (ref) {
            is JsTypeRef.Named ->
                if (ref.args.isEmpty()) substitution[ref.name] ?: ref
                else JsTypeRef.Named(ref.name, ref.args.map { substitute(it, substitution) })
            is JsTypeRef.Union -> JsTypeRef.Union(ref.members.map { substitute(it, substitution) }, ref.intersection)
            JsTypeRef.Unknown -> ref
        }
    }

    /** Expands union/intersection type aliases (`BodyInit` → its branches), cycle-capped. */
    private fun expandAliases(ref: JsTypeRef, depth: Int = 0): JsTypeRef {
        if (aliases.isEmpty() || depth > 8) return ref
        return when (ref) {
            is JsTypeRef.Named -> {
                val target = aliases[ref.name]
                when {
                    target != null -> expandAliases(JsTypeRef.parse(target), depth + 1)
                    ref.args.isEmpty() -> ref
                    else -> JsTypeRef.Named(ref.name, ref.args.map { expandAliases(it, depth + 1) })
                }
            }
            is JsTypeRef.Union -> JsTypeRef.Union(ref.members.map { expandAliases(it, depth + 1) }, ref.intersection)
            JsTypeRef.Unknown -> ref
        }
    }

    fun allGlobalNames(): Collection<String> = globals.keys
    fun allFunctionNames(): Collection<String> = functions.keys
    fun npmExportNames(packageName: String): Collection<String> = npmExports[packageName]?.keys ?: emptySet()

    /** TypeScript type for an npm export (e.g. `default` → `React.ComponentType`), if known from typings. */
    fun resolveNpmExportType(packageName: String, exportName: String): String? =
        npmExportTypes[packageName]?.get(exportName)

    /** Function overloads for a function-shaped npm/goog export, if the typings carried them. */
    fun resolveNpmExportMembers(packageName: String, exportName: String): List<JsMember>? =
        npmExportMembers[packageName]?.get(exportName)

    /** JSDoc attached to an npm/goog export's declaration. */
    fun resolveNpmExportDoc(packageName: String, exportName: String): String? =
        npmExportDocs[packageName]?.get(exportName)
    fun hasMemberName(memberName: String): Boolean = memberSamples.containsKey(memberName)

    fun getGoogNamespaceNames(): List<String> =
        npmExports.keys.filter { it == "goog" || it.startsWith("goog.") }.sorted()

    fun isKnownGoogNamespace(name: String): Boolean =
        (name == "goog" || name.startsWith("goog.")) && npmExports.containsKey(name)

    companion object {
        @JvmStatic fun getInstance(): JsSymbolIndex = service()
    }
}