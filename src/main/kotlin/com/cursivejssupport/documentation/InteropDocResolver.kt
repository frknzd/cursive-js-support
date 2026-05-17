package com.cursivejssupport.documentation

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NpmBinding
import com.cursivejssupport.npm.NsAliasResolver
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.util.JsResolveUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol

/**
 * Classifies a hovered `ClEditorSymbol` into an [InteropDocSubject].
 *
 * The resolver does the symbol-aware work the renderer would otherwise need to redo: walking
 * `js/foo.bar` chains, picking the right interface for `.method` / `.-prop` (including the
 * `sampleMembersByNamePrefix` fallback when the receiver type can't be inferred), and resolving
 * npm aliases through [NsAliasResolver]. The renderer ([InteropDocFormatter]) consumes the result
 * verbatim and is purely presentational.
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

        val receiverType = if (name.startsWith(".")) {
            val receiver = findReceiver(symbol)
            if (receiver != null) JsResolveUtil.resolveType(receiver, index) else null
        } else null

        val aliases = symbol.containingFile?.let { NsAliasResolver.resolveAliases(it) } ?: emptyMap()

        return resolveFromParts(namespace, name, receiverType, aliases, index)
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
        return when {
            namespace == "js" && name.contains('.') ->
                resolveJsChain(name, index) ?: resolveJsGlobal(name, index)
            namespace == "js" -> resolveJsGlobal(name, index)
            name.startsWith(".") -> resolveMemberFromParts(name, receiverType, index)
            namespace != null -> resolveNpmExportFromParts(namespace, name, aliases, index)
            else -> resolveBareAliasFromParts(name, aliases, index)
        }
    }

    // ─── js/* ──────────────────────────────────────────────────────────────

    private fun resolveJsGlobal(name: String, index: JsSymbolIndex): InteropDocSubject {
        index.resolveGlobalInfo(name)?.let { return InteropDocSubject.JsGlobal(name, it) }
        val overloads = index.resolveFunctions(name).orEmpty()
        val first = overloads.firstOrNull() ?: return InteropDocSubject.Unknown
        return InteropDocSubject.JsFunction(name, first, overloads.size)
    }

    private fun resolveJsChain(name: String, index: JsSymbolIndex): InteropDocSubject? {
        val segments = name.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val parentType = index.resolveJsChainType(segments.dropLast(1)) ?: return null
        val last = segments.last()
        val resolved = pickMember(parentType, last, asProperty = null, index) ?: return null
        return InteropDocSubject.JsChain(
            segments = segments,
            declaringType = resolved.first,
            member = resolved.second,
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
                    declaringType = hit.first,
                    member = hit.second,
                )
            }
        }

        // Receiver unknown (or wrong) — fall through to the cached prefix sampler. This is what
        // makes hover work for cases like `(.-commonAncestorContainer range)` where `range` is a
        // local without an inferable type.
        val samples = index.sampleMembersByNamePrefix(memberName, limit = 16)
            .filter { it.first == memberName }
        val best = samples.firstOrNull { sample ->
            val kind = sample.third.kind
            if (isProperty) kind == "property" else kind == "method"
        } ?: samples.firstOrNull() ?: return InteropDocSubject.Unknown

        return InteropDocSubject.Member(
            name = memberName,
            asProperty = isProperty,
            declaringType = sanitizeTypeName(best.second),
            member = best.third,
        )
    }

    /**
     * Look up [memberName] on [typeName], handling the TS `TYPE$X` companion sanitization
     * described in the class header. Returns `(displayTypeName, member)` or `null`.
     */
    private fun pickMember(
        typeName: String,
        memberName: String,
        asProperty: Boolean?,
        index: JsSymbolIndex,
    ): Pair<String, JsMember>? {
        // If the receiver type is a typeof-companion artifact, try the interface first.
        if (typeName.startsWith(TYPE_COMPANION_PREFIX)) {
            val unwrapped = typeName.removePrefix(TYPE_COMPANION_PREFIX)
            val ifaceHit = matchingOverload(unwrapped, memberName, asProperty, index)
            if (ifaceHit != null) return unwrapped to ifaceHit
            val companionHit = matchingOverload(typeName, memberName, asProperty, index)
            if (companionHit != null) return unwrapped to companionHit
            return null
        }
        val hit = matchingOverload(typeName, memberName, asProperty, index) ?: return null
        return sanitizeTypeName(typeName) to hit
    }

    /**
     * Returns the first overload of [memberName] on [typeName] that matches the requested kind.
     * When [asProperty] is `null` either kind is accepted, but properties are preferred only when
     * we'd otherwise have no candidate — methods come first in the iteration order of the index.
     */
    private fun matchingOverload(
        typeName: String,
        memberName: String,
        asProperty: Boolean?,
        index: JsSymbolIndex,
    ): JsMember? {
        val resolved = index.resolveMember(typeName, memberName) ?: return null
        val overloads = resolved.overloads
        if (asProperty == null) return overloads.firstOrNull()
        val want = if (asProperty) "property" else "method"
        return overloads.firstOrNull { it.kind == want } ?: overloads.firstOrNull()
    }

    // ─── npm exports ───────────────────────────────────────────────────────

    private fun resolveNpmExportFromParts(
        namespace: String,
        name: String,
        aliases: Map<String, NpmBinding>,
        index: JsSymbolIndex,
    ): InteropDocSubject {
        val pkg = aliases[namespace]?.packageName ?: return InteropDocSubject.Unknown
        if (!index.isKnownNpmExport(pkg, name)) return InteropDocSubject.Unknown
        return InteropDocSubject.NpmExport(
            packageName = pkg,
            exportName = name,
            type = index.resolveNpmExportType(pkg, name),
        )
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
            InteropDocSubject.NpmExport(
                packageName = pkg,
                exportName = exportKey,
                type = index.resolveNpmExportType(pkg, exportKey),
            )
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    /**
     * Walks past the open paren / whitespace to find the second child of the enclosing list —
     * the receiver of `(.method receiver ...)`.
     */
    private fun findReceiver(symbol: ClEditorSymbol): PsiElement? {
        val list = symbol.parent as? ClList ?: return null
        val children = list.children.filter {
            it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")"
        }
        return children.getOrNull(1)
    }

    /** Public for test access — strips the TS `TYPE$` companion artifact. */
    fun sanitizeTypeName(typeName: String): String =
        if (typeName.startsWith(TYPE_COMPANION_PREFIX)) typeName.removePrefix(TYPE_COMPANION_PREFIX)
        else typeName

    private const val TYPE_COMPANION_PREFIX = "TYPE\$"
}
