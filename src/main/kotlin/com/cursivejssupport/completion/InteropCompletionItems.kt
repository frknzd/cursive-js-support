package com.cursivejssupport.completion

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.index.JsEnvironment
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.util.JsResolveUtil
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import cursive.psi.api.ClList

/**
 * Builds [LookupElement]s for a given [InteropCompletionContext].
 *
 * Lookup strings are always the *short* name (member name, package name, export name). The
 * platform's [com.intellij.codeInsight.completion.PrefixMatcher] takes care of in-place
 * replacement backward by the context's prefix length, so the receiver / leading punctuation
 * the user already typed stays intact.
 *
 * Method elements use a small [InsertionContext]-aware handler that inserts `(` and a closing
 * `)` if the user is in head position and the lookup is a function/method.
 */
import com.cursivejssupport.semantic.InteropSemanticService
import com.cursivejssupport.npm.NsAliasResolver
import com.cursivejssupport.util.ChainKind
import com.cursivejssupport.util.InteropChainCore
import com.cursivejssupport.util.JsInteropChain
import com.cursivejssupport.util.JsInteropPsi
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager

object InteropCompletionItems {

    fun emit(
        context: InteropCompletionContext,
        file: PsiFile,
        index: JsSymbolIndex,
        result: CompletionResultSet,
        listAroundCaret: PsiElement?,
    ): Int = when (context) {
        is InteropCompletionContext.None -> 0
        is InteropCompletionContext.JsGlobalName -> emitJsGlobals(index, file, result)
        is InteropCompletionContext.JsChainMember -> emitJsChainMembers(context, index, result)
        is InteropCompletionContext.DotMember -> emitDotMembers(context, index, result, listAroundCaret)
        is InteropCompletionContext.NsRequirePackage -> 0 // handled by contributor (needs Project)
        is InteropCompletionContext.NsRequireRelativePackage -> 0 // handled by contributor (needs Project)
        is InteropCompletionContext.NsRequireKeyword -> emitRequireKeywords(context, result)
        is InteropCompletionContext.NsRefer -> emitRefer(context, file, index, result)
        is InteropCompletionContext.NpmAliasName -> emitNpmAliasNames(context, result)
        is InteropCompletionContext.NpmAliasExport -> emitNpmAliasExports(context, file, index, result)
        is InteropCompletionContext.NpmAliasExportMember -> emitNpmAliasExportMembers(context, file, index, result)
        is InteropCompletionContext.GoogNamespaceRequire -> emitGoogNamespaces(index, result)
        is InteropCompletionContext.GoogNamespaceName -> emitGoogNamespaces(index, result)
        is InteropCompletionContext.ChainStepForm -> emitChainStepMembers(context, file, index, result)
    }

    private fun emitNpmAliasNames(context: InteropCompletionContext.NpmAliasName, result: CompletionResultSet): Int {
        var n = 0
        for ((alias, binding) in context.availableAliases) {
            result.addElement(npmAliasNameLookup(alias, binding.packageName))
            n++
        }
        return n
    }

    /**
     * Public entry for the NpmRequirePackage slot — the contributor discovers the iterable
     * of package names (it needs the [com.intellij.openapi.project.Project] / resolver) and
     * delegates the lookup-element construction here.
     */
    fun emitNpmPackages(packages: Iterable<String>, result: CompletionResultSet): Int {
        var n = 0
        for (name in packages) {
            ProgressManager.checkCanceled()
            result.addElement(npmPackageLookup(name))
            n++
        }
        return n
    }

    /**
     * Complete `(:require ["./<prefix>"])` with `.js`/`.mjs`/`.cjs` files found under the build's
     * source paths and next to the requiring [file] (shadow-cljs namespace-relative imports).
     * The lookup string is the path as the user would type it (relative to the requiring file's
     * directory), so it round-trips into the buffer verbatim.
     */
    fun emitRelativeRequireFiles(file: PsiFile, prefix: String, result: CompletionResultSet): Int {
        val requiring = file.virtualFile?.parent ?: return 0
        val project = file.project
        val model = project.service<com.cursivejssupport.project.CljsProjectModel>()
        val seen = LinkedHashSet<String>()
        // Namespace-relative: paths the user would type relative to the requiring file's directory.
        collectRelativeFiles(requiring, requiring, prefix, seen)
        // Source-path-relative fallback: resolve the prefix against each source root and present
        // the result as a ./-prefixed path relative to the requiring file.
        for (profile in model.profiles) {
            for (src in profile.sourcePaths) {
                val srcDir = resolveSourceDir(project, src, profile.workingDirectory) ?: continue
                collectRelativeFiles(requiring, srcDir, prefix, seen)
            }
        }
        var n = 0
        for (path in seen) {
            result.addElement(relativeFileLookup(path))
            n++
        }
        return n
    }

    private fun collectRelativeFiles(
        requiringDir: com.intellij.openapi.vfs.VirtualFile,
        dir: com.intellij.openapi.vfs.VirtualFile,
        prefix: String,
        out: MutableSet<String>,
    ) {
        val cleanPrefix = prefix.removePrefix("./").removePrefix("/")
        // Walk up to the directory implied by the prefix (e.g. "./scripts/sub" → scripts/sub).
        val base = if (cleanPrefix.contains('/')) {
            val dirPart = cleanPrefix.substringBeforeLast('/').trimEnd('/')
            com.intellij.openapi.vfs.VfsUtil.findRelativeFile(dirPart, dir)?.takeIf { it.isDirectory } ?: return
        } else dir
        val filePrefix = cleanPrefix.substringAfterLast('/')
        for (child in base.children) {
            ProgressManager.checkCanceled()
            if (child.isDirectory) continue
            val name = child.name
            if (!name.endsWith(".js") && !name.endsWith(".mjs") && !name.endsWith(".cjs")) continue
            if (!name.startsWith(filePrefix)) continue
            // Present as a path relative to the requiring file's directory, ./-prefixed.
            val relative = com.intellij.openapi.vfs.VfsUtil.getRelativePath(requiringDir, child) ?: continue
            out.add("./$relative")
        }
    }

    private fun resolveSourceDir(
        project: com.intellij.openapi.project.Project,
        sourcePath: String,
        workingDirectory: String,
    ): com.intellij.openapi.vfs.VirtualFile? {
        val javaFile = java.io.File(sourcePath).let { if (it.isAbsolute) it else java.io.File(java.io.File(workingDirectory), sourcePath) }.normalize()
        return com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(javaFile)
    }

    private fun relativeFileLookup(path: String): LookupElement =
        LookupElementBuilder.create(path)
            .withPresentableText(path)
            .withTypeText("js")
            .withIcon(JsInteropCompletionIcons.forNpmNamespaceAlias())

    // ─── Producers ──────────────────────────────────────────────────────────

    private fun emitJsGlobals(index: JsSymbolIndex, file: PsiFile, result: CompletionResultSet): Int {
        if (!index.isLoaded) return 0
        // Filter globals by the requiring file's runtime target so `js/process` only appears in
        // Node-targeted files and `js/document` only in browser-targeted files. When no build
        // profile covers the file (unknown/mixed target) every environment is visible.
        val visible = JsEnvironment.visibleForTargets(
            com.cursivejssupport.project.CljsProjectModel.getInstance(file.project).runtimeTargetsForFile(file.virtualFile ?: return 0)
        )
        var n = 0
        for (name in index.allGlobalNames()) {
            ProgressManager.checkCanceled()
            val info = index.resolveGlobalInfo(name) ?: continue
            val env = JsEnvironment.fromWire(info.environment)
            if (!visible.contains(env)) continue
            val element = if (index.isConstructorGlobal(name)) {
                globalConstructorLookup(name, env.badge)
            } else {
                globalVariableLookup(name, info.type, env.badge)
            }
            result.addElement(element)
            n++
        }
        for (name in index.allFunctionNames()) {
            ProgressManager.checkCanceled()
            // Function globals carry environment on their first overload; reuse the same filter.
            val overloads = index.resolveFunctions(name).orEmpty()
            val env = JsEnvironment.fromWire(overloads.firstOrNull()?.environment)
            if (!visible.contains(env)) continue
            result.addElement(globalFunctionLookup(name, env.badge))
            n++
        }
        return n
    }

    private fun emitJsChainMembers(
        context: InteropCompletionContext.JsChainMember,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        if (!index.isLoaded) return 0
        val receiverType = index.resolveJsChainType(context.receiverSegments) ?: return 0
        return emitMembers(receiverType, index, result, asProperty = null)
    }

    private fun emitDotMembers(
        context: InteropCompletionContext.DotMember,
        index: JsSymbolIndex,
        result: CompletionResultSet,
        listAroundCaret: PsiElement?,
    ): Int {
        val receiver = receiverForDotMember(listAroundCaret)
        val resolution = if (receiver != null) JsResolveUtil.resolveTypeRef(receiver, index) else null
        if (!resolution?.effectiveSemanticMembers.isNullOrEmpty()) {
            var n = 0
            for (descriptor in resolution!!.effectiveSemanticMembers) {
                val want = if (context.asProperty) "property" else "method"
                if (descriptor.kind != want) continue
                result.addElement(memberLookup(
                    descriptor.name,
                    resolution.name,
                    JsMember(
                        kind = descriptor.kind,
                        params = descriptor.params,
                        returns = descriptor.returns,
                        type = descriptor.type,
                        doc = descriptor.doc,
                    ),
                    dotForm = true,
                ))
                n++
            }
            if (n > 0) return n
        }
        if (resolution != null && resolution.name.isNotEmpty()) {
            return emitMembers(resolution.name, index, result, asProperty = context.asProperty, dotForm = true)
        }
        if (!index.isLoaded) return 0
        // Receiver unknown — sample by member-name prefix.
        var n = 0
        for ((memberName, declaringType, member) in index.sampleMembersByNamePrefix(context.prefix)) {
            ProgressManager.checkCanceled()
            if (context.asProperty && member.kind != "property") continue
            if (!context.asProperty && member.kind != "method") continue
            result.addElement(memberLookup(memberName, declaringType, member, dotForm = true))
            n++
        }
        return n
    }

    private fun emitMembers(
        receiverType: String,
        index: JsSymbolIndex,
        result: CompletionResultSet,
        asProperty: Boolean?,
        dotForm: Boolean = false,
    ): Int {
        val members = index.resolveMembers(receiverType)
        var n = 0
        for ((memberName, resolved) in members) {
            ProgressManager.checkCanceled()
            val first = resolved.overloads.firstOrNull() ?: continue
            if (asProperty != null) {
                val want = if (asProperty) "property" else "method"
                if (first.kind != want) continue
            } else {
                if (first.kind != "method" && first.kind != "property") continue
            }
            result.addElement(memberLookup(memberName, resolved.declaringType, first, dotForm))
            n++
        }
        return n
    }

    private fun emitRequireKeywords(
        context: InteropCompletionContext.NsRequireKeyword,
        result: CompletionResultSet,
    ): Int {
        var n = 0
        for (kw in context.availableKeywords) {
            result.addElement(keywordLookup(kw))
            n++
        }
        return n
    }

    private fun emitRefer(
        context: InteropCompletionContext.NsRefer,
        file: PsiFile,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        var n = 0
        val indexedExports = index.npmExportNames(context.packageName)
        if (indexedExports.isNotEmpty()) {
            indexedExports.forEach {
                ProgressManager.checkCanceled()
                result.addElement(npmExportLookup(it, context.packageName)); n++
            }
            return n
        }

        // Only the package's named exports are valid here. Keyword helpers (`:as`, `:refer`,
        // `:rename`, `:default`) belong outside the `:refer` vector and surface through the
        // NsRequireKeyword slot instead. Live JavaScript PSI is a fallback for packages that the
        // background declaration index could not represent; keeping it off the common path makes
        // completion release its read action promptly.
        for (exportName in file.project.service<InteropSemanticService>().exportNames(file, context.packageName)) {
            ProgressManager.checkCanceled()
            result.addElement(npmExportLookup(exportName, context.packageName)); n++
        }
        return n
    }

    private fun emitNpmAliasExports(
        context: InteropCompletionContext.NpmAliasExport,
        file: PsiFile,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        var n = 0
        val indexedExports = index.npmExportNames(context.packageName)
        if (indexedExports.isNotEmpty()) {
            indexedExports.forEach {
                ProgressManager.checkCanceled()
                result.addElement(npmExportLookup(it, context.packageName)); n++
            }
            return n
        }

        for (exportName in file.project.service<InteropSemanticService>().exportNames(file, context.packageName)) {
            ProgressManager.checkCanceled()
            result.addElement(npmExportLookup(exportName, context.packageName)); n++
        }
        return n
    }

    private fun emitNpmAliasExportMembers(
        context: InteropCompletionContext.NpmAliasExportMember,
        file: PsiFile,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        // The immutable declaration snapshot answers the normal case without entering the
        // JavaScript plugin's resolver while completion owns a read action.
        if (index.isLoaded) {
            var receiverType = index.resolveNpmExportType(context.packageName, context.exportName)
            if (receiverType != null) {
                for (segment in context.receiverSegments) {
                    val currentType = receiverType ?: break
                    val member = index.resolveMember(currentType, segment)?.first
                    if (member == null) {
                        receiverType = null
                        break
                    }
                    receiverType = index.memberValueTypeName(member).ifEmpty { null }
                    if (receiverType == null) break
                }
                if (receiverType != null) {
                    val emitted = emitMembers(receiverType, index, result, asProperty = null)
                    if (emitted > 0) return emitted
                }
            }
        }

        // Use IntelliJ's own type evaluation as the fallback for packages whose typings the
        // background parser cannot digest.
        val descriptors = file.project.service<InteropSemanticService>().exportMembers(
            file, context.packageName, context.exportName, context.receiverSegments,
        )
        if (descriptors.isNotEmpty()) {
            var n = 0
            for (d in descriptors) {
                ProgressManager.checkCanceled()
                val member = JsMember(kind = d.kind, params = d.params, returns = d.returns, type = d.type, doc = d.doc)
                result.addElement(memberLookup(d.name, context.packageName, member, dotForm = false))
                n++
            }
            return n
        }
        return 0
    }

    private fun emitGoogNamespaces(index: JsSymbolIndex, result: CompletionResultSet): Int {
        if (!index.isLoaded) return 0
        var n = 0
        for (name in index.getGoogNamespaceNames()) {
            ProgressManager.checkCanceled()
            result.addElement(googNamespaceLookup(name))
            n++
        }
        return n
    }

    private fun emitChainStepMembers(
        context: InteropCompletionContext.ChainStepForm,
        file: PsiFile,
        index: JsSymbolIndex,
        result: CompletionResultSet,
    ): Int {
        if (!index.isLoaded) return 0
        if (context.priorChain.isEmpty()) return 0
        val kind = context.kind

        var receiverType = resolveRootToken(context.priorChain[0], file, index) ?: return 0
        // `doto` / `cond->` steps always receive the root value — prior steps don't matter.
        if (!kind.rootReceiver) {
            for (step in context.priorChain.drop(1)) {
                val spec = InteropChainCore.parseStepToken(step, kind) ?: return 0
                // `->>` list steps take their own first argument as receiver — not derivable
                // from document text alone, so the threaded type is unknown from here on.
                if (kind.threadsLast && spec.isListStep) return 0
                receiverType = InteropChainCore.advance(receiverType, spec.memberName, index) ?: return 0
            }
        }

        val members = index.resolveMembers(receiverType)
        var n = 0
        for ((memberName, resolved) in members) {
            ProgressManager.checkCanceled()
            val first = resolved.overloads.firstOrNull() ?: continue
            if (first.kind != "method" && first.kind != "property") continue
            result.addElement(chainStepMemberLookup(memberName, resolved.declaringType, first, kind))
            n++
        }
        return n
    }

    /**
     * Resolves the type of the root element of a `..` form from its document token text.
     * Handles `js/Global`, `js/global.member`, `alias/Export` (npm), and bare JS globals.
     */
    private fun resolveRootToken(token: String, file: PsiFile, index: JsSymbolIndex): String? {
        if (token.startsWith("js/")) {
            val segments = JsInteropChain.segmentsFromFullText(token)
            if (!segments.isNullOrEmpty()) return index.resolveJsChainType(segments)
            return null
        }
        val slashIdx = token.indexOf('/')
        if (slashIdx > 0) {
            val aliases = NsAliasResolver.resolveAliases(file)
            val alias = token.substring(0, slashIdx)
            val exportName = token.substring(slashIdx + 1)
            val binding = aliases[alias] ?: return null
            val t = index.resolveNpmExportType(binding.packageName, exportName) ?: return null
            return index.canonicalType(t)
        }
        return index.resolveGlobalType(token)?.let { index.canonicalType(it) }
    }

    // ─── Lookup builders ────────────────────────────────────────────────────

    private fun globalVariableLookup(name: String, type: String, badge: String? = null): LookupElement =
        LookupElementBuilder.create(name)
            .withPresentableText(name)
            .withTypeText(type)
            .withTailText(badge?.let { " ($it)" }.orEmpty())
            .withIcon(JsInteropCompletionIcons.forGlobalVariable())

    private fun globalConstructorLookup(name: String, badge: String? = null): LookupElement =
        LookupElementBuilder.create(name)
            .withPresentableText(name)
            .withTypeText("class")
            .withTailText(badge?.let { " ($it)" }.orEmpty())
            .withIcon(JsInteropCompletionIcons.forGlobalConstructor())
            .withInsertHandler(ConstructorInsertHandler)

    private fun globalFunctionLookup(name: String, badge: String? = null): LookupElement =
        LookupElementBuilder.create(name)
            .withPresentableText(name)
            .withTypeText("function")
            .withTailText(badge?.let { " ($it)" }.orEmpty())
            .withIcon(JsInteropCompletionIcons.forGlobalFunction())
            .withInsertHandler(CallHeadInsertHandler)

    private fun memberLookup(memberName: String, declaringType: String?, member: JsMember, dotForm: Boolean): LookupElement {
        val sig = if (member.kind == "method") {
            "(" + member.params.joinToString(", ") { p ->
                when {
                    p.rest -> "...${p.name}: ${p.type}"
                    p.optional -> "${p.name}?: ${p.type}"
                    else -> "${p.name}: ${p.type}"
                }
            } + ")"
        } else null
        val presentable = if (dotForm) (if (member.kind == "property") ".-$memberName" else ".$memberName") else memberName
        val typeText = declaringType ?: if (member.kind == "method") member.returns else member.type
        var builder = LookupElementBuilder.create(memberName)
            .withPresentableText(presentable)
            .withTypeText(typeText)
            .withIcon(JsInteropCompletionIcons.forJsMemberKind(member.kind))
        if (sig != null) builder = builder.withTailText(sig, true)
        if (member.kind == "method" && dotForm) builder = builder.withInsertHandler(CallHeadInsertHandler)
        if (presentable != memberName) builder = builder.withLookupString(presentable)
        return builder
    }

    /**
     * Lookup element for a chain-macro step. `..` steps are bare names with `-propName`
     * properties; threading/doto steps carry the leading `.` / `.-` — either way the lookup
     * string mirrors what the user types so [PlainPrefixMatcher] filters correctly.
     */
    private fun chainStepMemberLookup(memberName: String, declaringType: String?, member: JsMember, kind: ChainKind): LookupElement {
        val isProperty = member.kind == "property"
        val lookupString = when {
            kind.bareSteps -> if (isProperty) "-$memberName" else memberName
            else -> if (isProperty) ".-$memberName" else ".$memberName"
        }
        val sig = if (member.kind == "method") {
            "(" + member.params.joinToString(", ") { p ->
                when {
                    p.rest -> "...${p.name}: ${p.type}"
                    p.optional -> "${p.name}?: ${p.type}"
                    else -> "${p.name}: ${p.type}"
                }
            } + ")"
        } else null
        val typeText = declaringType ?: if (member.kind == "method") member.returns else member.type
        var builder = LookupElementBuilder.create(lookupString)
            .withPresentableText(lookupString)
            .withTypeText(typeText)
            .withIcon(JsInteropCompletionIcons.forJsMemberKind(member.kind))
        if (sig != null) builder = builder.withTailText(sig, true)
        return builder
    }

    private fun npmExportLookup(exportName: String, packageName: String): LookupElement {
        val icon = if (exportName == "default") {
            JsInteropCompletionIcons.forNpmDefaultExport()
        } else {
            JsInteropCompletionIcons.forNpmNamedExport()
        }
        return LookupElementBuilder.create(exportName)
            .withPresentableText(exportName)
            .withTypeText(packageName)
            .withIcon(icon)
    }

    private fun npmPackageLookup(packageName: String): LookupElement =
        LookupElementBuilder.create(packageName)
            .withPresentableText(packageName)
            .withTypeText("npm")
            .withIcon(JsInteropCompletionIcons.forNpmNamespaceAlias())

    private fun googNamespaceLookup(namespaceName: String): LookupElement =
        LookupElementBuilder.create(namespaceName)
            .withPresentableText(namespaceName)
            .withTypeText("goog")
            .withIcon(JsInteropCompletionIcons.forNpmNamespaceAlias())

    private fun npmAliasNameLookup(alias: String, packageName: String): LookupElement =
        LookupElementBuilder.create(alias)
            .withPresentableText(alias)
            .withTypeText(packageName)
            .withIcon(JsInteropCompletionIcons.forNpmNamespaceAlias())

    private fun keywordLookup(keyword: String): LookupElement =
        LookupElementBuilder.create(keyword)
            .withPresentableText(keyword)
            .withTypeText("ns")
            .withIcon(JsInteropCompletionIcons.forJsInteropRoot())
            .withInsertHandler(KeywordInsertHandler)

    // ─── PSI helpers ────────────────────────────────────────────────────────

    /**
     * Returns the second child of the enclosing list (the receiver of `(.method receiver ...)`).
     * Walks past whitespace / comments and the open paren.
     */
    private fun receiverForDotMember(listAroundCaret: PsiElement?): PsiElement? {
        val list = listAroundCaret as? ClList ?: return null
        val children = JsInteropPsi.meaningfulChildren(list)
        return children.getOrNull(1)
    }

    private object KeywordInsertHandler : com.intellij.codeInsight.completion.InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            // Add a trailing space so the user can keep typing the alias / refer collection.
            val editor = context.editor
            val doc = editor.document
            val offset = context.tailOffset
            if (offset >= doc.textLength || doc.charsSequence[offset] != ' ') {
                doc.insertString(offset, " ")
                editor.caretModel.moveToOffset(offset + 1)
            }
        }
    }

    private object CallHeadInsertHandler : com.intellij.codeInsight.completion.InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val doc = context.document
            val offset = context.tailOffset
            if (offset >= doc.textLength || doc.charsSequence[offset] !in setOf(' ', ')')) {
                doc.insertString(offset, " ")
                context.editor.caretModel.moveToOffset(offset + 1)
            } else if (offset < doc.textLength && doc.charsSequence[offset] == ')') {
                doc.insertString(offset, " ")
                context.editor.caretModel.moveToOffset(offset + 1)
            }
        }
    }

    private object ConstructorInsertHandler : com.intellij.codeInsight.completion.InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val doc = context.document
            var offset = context.tailOffset
            if (offset >= doc.textLength || doc.charsSequence[offset] != '.') {
                doc.insertString(offset, ".")
                offset++
            }
            if (offset >= doc.textLength || doc.charsSequence[offset] == ')') {
                doc.insertString(offset, " ")
                offset++
            }
            context.editor.caretModel.moveToOffset(offset)
        }
    }
}
