package com.cursivejssupport.inspection

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NpmBindingKind
import com.cursivejssupport.semantic.InteropSemanticService
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.util.JsInteropChain
import com.cursivejssupport.util.JsResolveUtil
import com.cursivejssupport.util.JsInteropPsi
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol

class JsInteropInspection : LocalInspectionTool() {

    override fun getShortName(): String = "ClojureScriptJsInterop"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val symbol = element as? ClEditorSymbol ?: return
                val index = JsSymbolIndex.getInstance(symbol.project)
                inspectJsGlobal(symbol, index, holder)
                inspectDotCall(symbol, index, holder)
                inspectNpmExport(symbol, index, holder)
                inspectImportedBinding(symbol, index, holder)
                inspectDirectCall(symbol, index, holder)
            }
        }

    private fun inspectJsGlobal(symbol: ClEditorSymbol, index: JsSymbolIndex, holder: ProblemsHolder) {
        if (!index.isLoaded) return
        val full = symbol.text.removeSuffix(".")
        if (!full.startsWith("js/")) return
        val segments = JsInteropChain.segmentsFromFullText(full) ?: return
        val global = segments.firstOrNull() ?: return
        if (!index.isKnownGlobal(global)) {
            val replacement = nearest(global, index.allGlobalNames())
            holder.registerProblem(
                symbol,
                "Unknown JavaScript global '$global'",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                *replacement?.let { arrayOf(ReplaceInteropSymbolFix(global, it)) }.orEmpty(),
            )
            return
        }
        if (segments.size > 1 && index.resolveJsChainTypeRef(segments) == null) {
            val parentType = index.resolveJsChainType(segments.dropLast(1)) ?: return
            registerUnknownMember(symbol, parentType, segments.last(), index, holder)
        }
        if (symbol.text.endsWith(".") && !index.isConstructorGlobal(global)) {
            holder.registerProblem(symbol, "'$global' is not constructable", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        }
    }

    private fun inspectDirectCall(symbol: ClEditorSymbol, index: JsSymbolIndex, holder: ProblemsHolder) {
        if (!index.isLoaded) return
        val list = symbol.parent as? ClList ?: return
        if (JsInteropPsi.meaningfulChildren(list).firstOrNull() !== symbol) return
        val raw = symbol.text
        if (!raw.startsWith("js/") || raw.endsWith(".") || '.' in raw.removePrefix("js/")) return
        val name = raw.removePrefix("js/")
        if (!index.isKnownGlobal(name) || index.resolveFunctions(name) != null) return
        val fixes = if (index.isConstructorGlobal(name)) arrayOf(ReplaceInteropSymbolFix(raw, "$raw.")) else emptyArray()
        holder.registerProblem(
            symbol,
            "JavaScript value '$name' is not callable",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            *fixes,
        )
    }

    private fun inspectImportedBinding(symbol: ClEditorSymbol, index: JsSymbolIndex, holder: ProblemsHolder) {
        val semantics = symbol.project.service<InteropSemanticService>()
        val binding = semantics.bindings(symbol.containingFile)[symbol.text] ?: return
        val declaration = semantics.bindingDeclaration(symbol.containingFile, symbol.text) ?: return
        if (declaration.textRange != symbol.textRange) return
        val availableNames = semantics.exportNames(symbol.containingFile, binding.packageName)
        if (availableNames.isEmpty() && !index.isLoaded) return
        when (binding.kind) {
            NpmBindingKind.REFER -> {
                val export = binding.exportName ?: symbol.text
                if (!semantics.hasExport(symbol.containingFile, binding.packageName, export)) {
                    val replacement = nearest(export, availableNames)
                    holder.registerProblem(
                        symbol,
                        "Package '${binding.packageName}' has no export '$export'",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        *replacement?.let { arrayOf(ReplaceInteropSymbolFix(symbol.text, it)) }.orEmpty(),
                    )
                }
            }
            NpmBindingKind.DEFAULT -> if (!semantics.hasExport(symbol.containingFile, binding.packageName, "default")) {
                holder.registerProblem(symbol, "Package '${binding.packageName}' has no default export", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
            }
            NpmBindingKind.AS, NpmBindingKind.ALL -> if (availableNames.isEmpty()) {
                holder.registerProblem(symbol, "No TypeScript declarations found for '${binding.packageName}'", ProblemHighlightType.WEAK_WARNING)
            }
        }
    }

    private fun inspectDotCall(symbol: ClEditorSymbol, index: JsSymbolIndex, holder: ProblemsHolder) {
        val list = symbol.parent as? ClList ?: return
        val children = JsInteropPsi.meaningfulChildren(list)
        if (children.firstOrNull() !== symbol || !symbol.text.startsWith(".")) return
        val receiver = children.getOrNull(1) ?: return
        val receiverType = JsResolveUtil.resolveTypeRef(receiver, index) ?: return
        if (!receiverType.confident) return
        val memberName = symbol.text.removePrefix(".").removePrefix("-")
        val semanticOverloads = receiverType.semanticMembers.filter { it.name == memberName }.map {
            JsMember(kind = it.kind, params = it.params, returns = it.returns, type = it.type, doc = it.doc)
        }
        val resolved = if (semanticOverloads.isEmpty()) index.resolveMembersOf(receiverType.ref)[memberName] else null
        if (semanticOverloads.isEmpty() && resolved == null) {
            if (receiverType.semanticMembers.isNotEmpty()) {
                val replacement = nearest(memberName, receiverType.semanticMembers.map { it.name })
                holder.registerProblem(
                    symbol, "Type '${receiverType.name}' has no member '$memberName'",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    *replacement?.let { arrayOf(ReplaceInteropSymbolFix(symbol.text, symbol.text.replace(memberName, it))) }.orEmpty(),
                )
                return
            }
            if (!index.isLoaded) return
            registerUnknownMember(symbol, receiverType.name, memberName, index, holder)
            return
        }
        val overloads = semanticOverloads.ifEmpty { resolved?.overloads.orEmpty() }
        val member = overloads.firstOrNull() ?: return
        val propertySyntax = symbol.text.startsWith(".-")
        if (propertySyntax && member.kind == "method") {
            holder.registerProblem(
                symbol,
                "'$memberName' is a method, not a property",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                ReplaceInteropSymbolFix(symbol.text, ".$memberName"),
            )
        } else if (!propertySyntax && member.kind != "method") {
            holder.registerProblem(
                symbol,
                "'$memberName' is a property, not a method",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                ReplaceInteropSymbolFix(symbol.text, ".-$memberName"),
            )
        }
        if (member.doc?.contains("@deprecated", ignoreCase = true) == true) {
            holder.registerProblem(symbol, "'$memberName' is deprecated", ProblemHighlightType.LIKE_DEPRECATED)
        }
        if (member.kind == "method" && !propertySyntax) inspectArity(symbol, children.size - 2, overloads, holder)
    }

    private fun inspectArity(symbol: PsiElement, argumentCount: Int, overloads: List<JsMember>, holder: ProblemsHolder) {
        if (overloads.isEmpty()) return
        val accepts = overloads.any { member ->
            val required = member.params.count { !it.optional && !it.rest }
            val max = if (member.params.any { it.rest }) Int.MAX_VALUE else member.params.size
            argumentCount in required..max
        }
        if (!accepts) {
            val expected = overloads.map { member ->
                val required = member.params.count { !it.optional && !it.rest }
                if (member.params.any { it.rest }) "$required+" else if (required == member.params.size) "$required" else "$required-${member.params.size}"
            }.distinct().joinToString(" or ")
            holder.registerProblem(symbol, "Wrong argument count: got $argumentCount, expected $expected", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
        }
    }

    private fun inspectNpmExport(symbol: ClEditorSymbol, index: JsSymbolIndex, holder: ProblemsHolder) {
        val namespace = symbol.namespace ?: return
        val semantics = symbol.project.service<InteropSemanticService>()
        val binding = semantics.bindings(symbol.containingFile)[namespace] ?: return
        if (binding.kind !in setOf(NpmBindingKind.AS, NpmBindingKind.ALL)) return
        val exportName = symbol.name?.substringBefore('.') ?: return
        if (exportName.isBlank()) return
        val names = semantics.exportNames(symbol.containingFile, binding.packageName)
        if (names.isEmpty() && !index.isLoaded || exportName in names) return
        val replacement = nearest(exportName, names)
        holder.registerProblem(
            symbol,
            "Package '${binding.packageName}' has no export '$exportName'",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            *replacement?.let { arrayOf(ReplaceInteropSymbolFix(exportName, it)) }.orEmpty(),
        )
    }

    private fun registerUnknownMember(
        symbol: PsiElement,
        receiverType: String,
        memberName: String,
        index: JsSymbolIndex,
        holder: ProblemsHolder,
    ) {
        val members = index.resolveMembers(receiverType).keys
        val replacement = nearest(memberName, members)
        holder.registerProblem(
            symbol,
            "Type '$receiverType' has no member '$memberName'",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            *replacement?.let { arrayOf(ReplaceInteropSymbolFix(memberName, it)) }.orEmpty(),
        )
    }

}

class ReplaceInteropSymbolFix(private val oldText: String, private val newText: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace '$oldText' with '$newText'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return
        val range = element.textRange
        val relative = element.text.indexOf(oldText)
        if (relative < 0) return
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(range.startOffset + relative, range.startOffset + relative + oldText.length, newText)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }
}

internal fun nearest(value: String, candidates: Collection<String>): String? = candidates
    .asSequence()
    .map { it to editDistance(value, it) }
    .filter { (_, distance) -> distance <= maxOf(2, value.length / 3) }
    .minWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
    ?.first

internal fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val current = IntArray(b.length + 1)
        current[0] = i + 1
        for (j in b.indices) {
            current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
        }
        previous = current
    }
    return previous[b.length]
}
