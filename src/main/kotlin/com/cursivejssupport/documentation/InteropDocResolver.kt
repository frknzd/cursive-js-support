package com.cursivejssupport.documentation

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NpmBinding
import com.cursivejssupport.semantic.InteropSemanticService
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.util.InteropChains
import com.cursivejssupport.util.JsResolveUtil
import com.cursivejssupport.util.JsInteropPsi
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol

/**
 * Classifies a hovered `ClEditorSymbol` into an [InteropDocSubject].
 *
 * The resolver does the symbol-aware work the renderer would otherwise need to redo: walking
 * `js/foo.bar` chains, picking the right interface for `.method` / `.-prop` (falling back to the
 * full ambiguous candidate set via [JsSymbolIndex.memberDeclarations] when the receiver type
 * can't be inferred), and resolving npm aliases through [NsAliasResolver]. The renderer
 * ([InteropDocFormatter]) consumes the result verbatim and is purely presentational.
 *
 * **TypeScript artifact sanitization.** Some receivers (e.g. `js/NodeFilter`, which is a `const`
 * value typed by an anonymous `typeof` companion) resolve to a TS extractor artifact like
 * `TYPE$NodeFilter`. We treat that as "the companion of `NodeFilter`":
 *
 *  1. Try `NodeFilter` (interface) first; if it owns the member, use that — interfaces win.
 *  2. Otherwise look on the `TYPE$X` companion, but display the declaring type as `X` so the
 *     popup shows `NodeFilter#SHOW_ELEMENT` rather than `TYPE$NodeFilter#.-SHOW_ELEMENT: any`.
 *
 * That ordering also prevents the cross-talk where an unrelated method's JSDoc would otherwise
 * bleed into a property's docs — the wrong-interface hit never happens because we never look up
 * the property on an interface that doesn't own it.
 */
object InteropDocResolver {

    fun resolve(
        symbol: ClEditorSymbol,
        index: JsSymbolIndex,
    ): InteropDocSubject {
        if (!index.isLoaded) return InteropDocSubject.Unknown

        val namespace = symbol.namespace
        val name = symbol.name ?: symbol.text?.trim().orEmpty()
        if (name.isEmpty()) return InteropDocSubject.Unknown

        // Steps inside chain forms — (.. root …), (-> root …), (doto root …) — resolve the
        // receiver by walking the prior chain. Falls through to the generic member resolution
        // below (sampled fallback) when the chain can't be resolved.
        InteropChains.stepContext(symbol, index)?.let { ctx ->
            val receiverType = ctx.receiverType
            if (receiverType != null) {
                val hit = pickMember(receiverType, ctx.memberName, asProperty = ctx.isProperty, index)
                if (hit != null) {
                    return InteropDocSubject.Member(
                        name = ctx.memberName,
                        asProperty = ctx.isProperty,
                        declaringType = hit.displayType,
                        member = hit.primary,
                        overloads = hit.overloads,
                    )
                }
            }
            // Chain receiver unknown (or it didn't own the member) — bare step names would
            // otherwise dead-end below, so offer the full ambiguous candidate set here.
            val ambiguous = resolveAmbiguousMember(ctx.memberName, ctx.isProperty, receiverType, index)
            if (ambiguous != InteropDocSubject.Unknown) return ambiguous
        }

        var receiverText: String? = null
        val receiverResolution = if (name.startsWith(".")) {
            val receiver = findReceiver(symbol)
            receiverText = receiver?.text?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("(") }
            if (receiver != null) JsResolveUtil.resolveTypeRef(receiver, index) else null
        } else null
        if (receiverResolution != null && name.startsWith(".")) {
            val memberName = name.removePrefix(".").removePrefix("-")
            val descriptors = receiverResolution.effectiveSemanticMembers.filter { it.name == memberName }
            if (descriptors.isNotEmpty()) {
                val overloads = descriptors.map {
                    JsMember(kind = it.kind, params = it.params, returns = it.returns, type = it.type, doc = it.doc)
                }
                return InteropDocSubject.Member(
                    memberName, name.startsWith(".-"), receiverResolution.ref.display(), overloads.first(), overloads,
                    receiverText,
                )
            }
        }
        val receiverType = receiverResolution?.name

        val aliases = symbol.containingFile?.let { symbol.project.service<InteropSemanticService>().bindings(it) } ?: emptyMap()

        val subject = resolveFromParts(namespace, name, receiverType, aliases, index)
        if (subject is InteropDocSubject.Member && receiverText != null) {
            return subject.copy(receiverText = receiverText)
        }
        // npm exports the index couldn't describe: ask the IntelliJ JS type sources (available
        // when the JavaScript plugin is installed) for signatures / a type display.
        if (subject is InteropDocSubject.NpmExport && subject.overloads.isEmpty()) {
            symbol.containingFile?.let { file ->
                val semantics = symbol.project.service<InteropSemanticService>()
                val descriptors = semantics.exportMembers(file, subject.packageName, subject.exportName)
                val fnDescriptor = descriptors.singleOrNull()?.takeIf { it.name == subject.exportName && it.kind == "method" }
                val overloads = fnDescriptor?.let {
                    listOf(JsMember(kind = "method", params = it.params, returns = it.returns, doc = it.doc))
                }.orEmpty()
                val typeDisplay = subject.type
                    ?: semantics.exportType(file, subject.packageName, subject.exportName)?.displayName
                if (overloads.isNotEmpty() || typeDisplay != subject.type) {
                    return subject.copy(type = typeDisplay, overloads = overloads)
                }
            }
        }
        return subject
    }

    /**
     * Pure-input entry point used by tests. The PSI-driven entry point above resolves the
     * receiver type / namespace alias map ahead of time and then dispatches here.
     */
    fun resolveFromParts(
        namespace: String?,
        name: String,
        receiverType: String?,
        aliases: Map<String, NpmBinding>,
        index: JsSymbolIndex,
    ): InteropDocSubject {
        if (name.isEmpty()) return InteropDocSubject.Unknown
        // Constructor call head: `(Foo. …)` / `(js/Foo. …)`.
        if (name.endsWith(".") && name.length > 1 && !name.startsWith(".")) {
            resolveConstructor(name.removeSuffix("."), index)?.let { return it }
        }
        return when {
            namespace == "js" && name.contains('.') ->
                resolveJsChain(name, index) ?: resolveJsGlobal(name, index)
            namespace == "js" -> resolveJsGlobal(name, index)
            name.startsWith(".") -> resolveMemberFromParts(name, receiverType, index)
            namespace != null -> resolveNpmExportFromParts(namespace, name, aliases, index)
            else -> resolveBareAliasFromParts(name, aliases, index)
        }
    }

    /**
     * `(Foo. …)` — resolve the `new` overloads on whichever companion declares them: the class's
     * `TYPE$Foo$Static`, the interface itself, or the type the global is declared with
     * (`declare var Error: ErrorConstructor`, how core ECMAScript spells it).
     */
    private fun resolveConstructor(typeName: String, index: JsSymbolIndex): InteropDocSubject? {
        val cleanName = typeName.removePrefix("js/")
        val companion = "$TYPE_COMPANION_PREFIX$cleanName\$Static"
        val newOverloads = index.resolveMember(companion, "new")?.overloads
            ?: index.resolveMember(cleanName, "new")?.overloads
            ?: index.globalConstructSignatures(cleanName).ifEmpty { null }
        val classDoc = index.resolveGlobalInfo(cleanName)?.doc
            ?: index.resolveInterface(cleanName)?.members?.get("constructor")?.firstOrNull()?.doc
        if (newOverloads.isNullOrEmpty() && index.resolveInterface(cleanName) == null) return null
        return InteropDocSubject.Constructor(
            typeName = cleanName,
            overloads = newOverloads.orEmpty(),
            doc = newOverloads?.firstOrNull()?.doc ?: classDoc,
        )
    }

    // ─── js/* ──────────────────────────────────────────────────────────────

    private fun resolveJsGlobal(name: String, index: JsSymbolIndex): InteropDocSubject {
        index.resolveGlobalInfo(name)?.let {
            return InteropDocSubject.JsGlobal(name, it, isConstructor = index.isConstructorGlobal(name))
        }
        val overloads = index.resolveFunctions(name).orEmpty()
        val first = overloads.firstOrNull() ?: return InteropDocSubject.Unknown
        return InteropDocSubject.JsFunction(name, first, overloads)
    }

    private fun resolveJsChain(name: String, index: JsSymbolIndex): InteropDocSubject? {
        val segments = name.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val parentType = index.resolveJsChainType(segments.dropLast(1)) ?: return null
        val last = segments.last()
        val resolved = pickMember(parentType, last, asProperty = null, index) ?: return null
        return InteropDocSubject.JsChain(
            segments = segments,
            declaringType = resolved.displayType,
            member = resolved.primary,
            overloads = resolved.overloads,
        )
    }

    // ─── .method / .-prop ──────────────────────────────────────────────────

    private fun resolveMemberFromParts(name: String, receiverType: String?, index: JsSymbolIndex): InteropDocSubject {
        val isProperty = name.startsWith(".-")
        val memberName = name.removePrefix(".").removePrefix("-")
        if (memberName.isEmpty()) return InteropDocSubject.Unknown

        if (receiverType != null) {
            val hit = pickMember(receiverType, memberName, asProperty = isProperty, index)
            if (hit != null) {
                return InteropDocSubject.Member(
                    name = memberName,
                    asProperty = isProperty,
                    declaringType = hit.displayType,
                    member = hit.primary,
                    overloads = hit.overloads,
                )
            }
        }

        // Receiver unknown (or wrong) — list every interface that declares the member, best
        // candidate first. This is what makes hover work for cases like
        // `(.-commonAncestorContainer range)` where `range` is a local without an inferable type.
        return resolveAmbiguousMember(memberName, isProperty, receiverType, index)
    }

    /**
     * Member access whose receiver type is unknown (or didn't own the member): the primary
     * subject is the best-ranked declaring interface, and every other declaring interface is
     * carried in [InteropDocSubject.Member.alternatives] so the popup can list all candidates —
     * mirroring go-to-declaration, which offers the full destination set in this situation.
     */
    private fun resolveAmbiguousMember(
        memberName: String,
        isProperty: Boolean,
        receiverType: String?,
        index: JsSymbolIndex,
    ): InteropDocSubject {
        val declarations = index.memberDeclarations(memberName)
        if (declarations.isEmpty()) return InteropDocSubject.Unknown

        // Interface-first companion suppression: drop `TYPE$X` when plain `X` also declares the
        // member (same rule as pickMember).
        val filtered = declarations.filterNot { d ->
            d.declaringType.startsWith(TYPE_COMPANION_PREFIX) &&
                declarations.any { it.declaringType == sanitizeTypeName(d.declaringType) }
        }.ifEmpty { declarations }

        val wantKind = if (isProperty) "property" else "method"
        val ranked = filtered.sortedWith(
            compareBy(
                { d -> if (d.overloads.any { it.kind == wantKind }) 0 else 1 },
                { d -> receiverAffinity(d.declaringType, receiverType, index) },
                { d -> if (d.declaringType.startsWith(TYPE_COMPANION_PREFIX)) 1 else 0 },
                { d -> d.declaringType },
            )
        )

        val primary = ranked.first()
        val primaryOverload = primary.overloads.firstOrNull { it.kind == wantKind }
            ?: primary.overloads.firstOrNull()
            ?: return InteropDocSubject.Unknown
        return InteropDocSubject.Member(
            name = memberName,
            asProperty = isProperty,
            declaringType = sanitizeTypeName(primary.declaringType),
            member = primaryOverload,
            overloads = orderedOverloads(primaryOverload, primary.overloads),
            alternatives = ranked.drop(1).map {
                InteropDocSubject.MemberGroup(sanitizeTypeName(it.declaringType), it.overloads)
            },
        )
    }

    /** 0 = the receiver itself, 1 = a direct base of the receiver, 2 = unrelated. */
    private fun receiverAffinity(declaringType: String, receiverType: String?, index: JsSymbolIndex): Int =
        when {
            receiverType == null -> 2
            declaringType == receiverType -> 0
            index.resolveInterface(receiverType)?.extends?.contains(declaringType) == true -> 1
            else -> 2
        }

    /** Resolved member with its display type and the full overload list, primary first. */
    private data class MemberHit(val displayType: String, val primary: JsMember, val overloads: List<JsMember>)

    /**
     * Look up [memberName] on [typeName], handling the TS `TYPE$X` companion sanitization
     * described in the class header.
     */
    private fun pickMember(
        typeName: String,
        memberName: String,
        asProperty: Boolean?,
        index: JsSymbolIndex,
    ): MemberHit? {
        // If the receiver type is a typeof-companion artifact, try the interface first.
        if (typeName.startsWith(TYPE_COMPANION_PREFIX)) {
            val unwrapped = typeName.removePrefix(TYPE_COMPANION_PREFIX)
            return matchingOverloads(unwrapped, memberName, asProperty, index)?.withDisplayType(unwrapped)
                ?: matchingOverloads(typeName, memberName, asProperty, index)?.withDisplayType(unwrapped)
        }
        return matchingOverloads(typeName, memberName, asProperty, index)
            ?.withDisplayType(sanitizeTypeName(typeName))
    }

    private fun MemberHit.withDisplayType(type: String) = copy(displayType = type)

    /**
     * Resolves [memberName] on [typeName] with the primary overload chosen by kind preference:
     * when [asProperty] is set, an overload of the requested kind wins; otherwise the index's
     * first overload. The rest of the overloads follow the primary in index order.
     */
    private fun matchingOverloads(
        typeName: String,
        memberName: String,
        asProperty: Boolean?,
        index: JsSymbolIndex,
    ): MemberHit? {
        val resolved = index.resolveMember(typeName, memberName) ?: return null
        val overloads = resolved.overloads
        val primary = if (asProperty == null) {
            overloads.firstOrNull()
        } else {
            val want = if (asProperty) "property" else "method"
            overloads.firstOrNull { it.kind == want } ?: overloads.firstOrNull()
        } ?: return null
        return MemberHit(resolved.declaringType, primary, orderedOverloads(primary, overloads))
    }

    private fun orderedOverloads(primary: JsMember, overloads: List<JsMember>): List<JsMember> =
        listOf(primary) + overloads.filter { it !== primary }

    // ─── npm exports ───────────────────────────────────────────────────────

    private fun resolveNpmExportFromParts(
        namespace: String,
        name: String,
        aliases: Map<String, NpmBinding>,
        index: JsSymbolIndex,
    ): InteropDocSubject {
        val pkg = aliases[namespace]?.packageName
            ?: (if (index.isKnownGoogNamespace(namespace)) namespace else null)
            ?: return InteropDocSubject.Unknown
        if (!index.isKnownNpmExport(pkg, name)) return InteropDocSubject.Unknown
        return npmExportSubject(pkg, name, index)
    }

    private fun resolveBareAliasFromParts(
        name: String,
        aliases: Map<String, NpmBinding>,
        index: JsSymbolIndex,
    ): InteropDocSubject {
        val pkg = aliases[name]?.packageName ?: return InteropDocSubject.Unknown
        val exportKey = when {
            index.isKnownNpmExport(pkg, "default") -> "default"
            index.isKnownNpmExport(pkg, name) -> name
            else -> index.npmExportNames(pkg).firstOrNull()
        }
        return if (exportKey == null) {
            InteropDocSubject.NpmAlias(alias = name, packageName = pkg)
        } else {
            npmExportSubject(pkg, exportKey, index)
        }
    }

    /** Enriched npm/goog export subject: type, function overloads, and declaration JSDoc. */
    private fun npmExportSubject(pkg: String, exportName: String, index: JsSymbolIndex): InteropDocSubject.NpmExport {
        val overloads = index.resolveNpmExportMembers(pkg, exportName).orEmpty()
        val doc = index.resolveNpmExportDoc(pkg, exportName)
        return InteropDocSubject.NpmExport(
            packageName = pkg,
            exportName = exportName,
            type = index.resolveNpmExportType(pkg, exportName),
            overloads = overloads,
            doc = doc,
        )
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    /**
     * Walks past the open paren / whitespace to find the second child of the enclosing list —
     * the receiver of `(.method receiver ...)`.
     */
    private fun findReceiver(symbol: ClEditorSymbol): PsiElement? {
        val list = symbol.parent as? ClList ?: return null
        val children = JsInteropPsi.meaningfulChildren(list)
        return children.getOrNull(1)
    }

    /** Public for test access — strips the TS `TYPE$` companion artifact. */
    fun sanitizeTypeName(typeName: String): String =
        if (typeName.startsWith(TYPE_COMPANION_PREFIX)) typeName.removePrefix(TYPE_COMPANION_PREFIX)
        else typeName

    private const val TYPE_COMPANION_PREFIX = "TYPE\$"
}
