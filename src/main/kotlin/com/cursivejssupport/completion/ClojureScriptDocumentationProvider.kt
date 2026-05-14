package com.cursivejssupport.completion

import com.cursivejssupport.documentation.JsDocHtmlFormatter
import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NsAliasResolver
import com.cursivejssupport.parser.JsMember
import com.cursivejssupport.util.JsResolveUtil
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol

class ClojureScriptDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element is com.cursivejssupport.reference.JsSymbolPsiElement) {
            return docForJsSymbolPsiElement(element)
        }

        val sym = findClojureSymbol(originalElement)
            ?: findClojureSymbol(element)
            ?: return null

        val index = JsSymbolIndex.getInstance()
        if (!index.isLoaded) return null

        val namespace = sym.namespace
        val name = sym.name ?: sym.text?.trim() ?: return null

        return when {
            namespace == "js" && name.contains('.') -> docForJsChainGlobal(name, index) ?: docForJsGlobal(name, index)
            namespace == "js"    -> docForJsGlobal(name, index)
            name.startsWith(".") -> docForMember(sym, name, index)
            namespace != null    -> docForNpmExport(sym, namespace, name, index)
            else                 -> docForBareNpmAlias(sym, name, index)
        }
    }

    private fun docForJsSymbolPsiElement(element: com.cursivejssupport.reference.JsSymbolPsiElement): String? {
        val member = element.member
        val definition = when {
            member != null && member.kind == "method" -> {
                val sig = buildParamsSig(member)
                if (element.type == "function") "${element.name}($sig): ${member.returns}"
                else "${element.type}#.${element.name}($sig): ${member.returns}"
            }
            member != null -> "${element.type}#.-${element.name}: ${member.type}"
            element.packageName != null -> {
                val exp = element.npmExportName
                if (!exp.isNullOrBlank()) "${element.name} (export $exp from ${element.packageName})"
                else "${element.name} (from ${element.packageName})"
            }
            else -> "js/${element.name}: ${element.type}"
        }
        return buildDoc(definition, element.doc ?: member?.doc)
    }

    private fun findClojureSymbol(element: PsiElement?): ClEditorSymbol? {
        if (element == null) return null
        return PsiTreeUtil.getParentOfType(element, ClEditorSymbol::class.java, false)
    }

    private fun docForJsChainGlobal(name: String, index: JsSymbolIndex): String? {
        val segments = name.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val parentType = index.resolveJsChainType(segments.dropLast(1)) ?: return null
        val last = segments.last()
        val member = index.resolveInterface(parentType)?.members?.get(last)?.firstOrNull() ?: return null
        return buildDocForMember(parentType, last, member)
    }

    // ─── js/ globals ─────────────────────────────────────────────────────────

    private fun docForJsGlobal(name: String, index: JsSymbolIndex): String? {
        val varInfo = index.resolveGlobalInfo(name)
        if (varInfo != null) {
            return buildDoc("js/$name: ${varInfo.type}", varInfo.doc)
        }
        val overloads = index.resolveFunctions(name) ?: return null
        val first = overloads.firstOrNull() ?: return null
        return buildDoc("js/${buildFnSig(name, first)}", first.doc)
    }

    // ─── member access (.method / .-prop) ────────────────────────────────────

    private fun docForMember(sym: ClEditorSymbol, name: String, index: JsSymbolIndex): String? {
        val list     = sym.parent as? ClList ?: return null
        val children = list.children.filter { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" }
        val receiver = children.getOrNull(1)

        val memberName = name.removePrefix(".").removePrefix("-")

        val typeName = JsResolveUtil.resolveType(receiver, index)
        if (typeName != null) {
            val iface    = index.resolveInterface(typeName) ?: return null
            val overloads  = iface.members[memberName] ?: return null
            val first      = overloads.firstOrNull() ?: return null
            return buildDocForMember(typeName, memberName, first)
        } else {
            // Pooled path: no clear receiver type, look in all interfaces
            // Limit to avoid performance issues
            val interfaces = index.allInterfaces()
            if (interfaces.size < 500) {
                for ((ifName, iface) in interfaces) {
                    val overloads = iface.members[memberName] ?: continue
                    val first = overloads.firstOrNull() ?: continue
                    return buildDocForMember(ifName, memberName, first)
                }
            }
        }
        return null
    }

    private fun buildDocForMember(typeName: String, memberName: String, member: JsMember): String {
        val def = when (member.kind) {
            "method" -> {
                val sig = buildParamsSig(member)
                ".$memberName($sig): ${member.returns}"
            }
            else -> ".-$memberName: ${member.type}"
        }
        return buildDoc("$typeName#$def", member.doc)
    }

    // ─── npm alias/X ─────────────────────────────────────────────────────────

    private fun docForNpmExport(sym: ClEditorSymbol, namespace: String, name: String, index: JsSymbolIndex): String? {
        val file    = sym.containingFile ?: return null
        val aliases = NsAliasResolver.resolveAliases(file)
        val pkg     = aliases[namespace] ?: return null
        if (!index.isKnownNpmExport(pkg, name)) return null
        return buildDoc("$name (from $pkg)", null)
    }

    private fun docForBareNpmAlias(sym: ClEditorSymbol, symbolName: String, index: JsSymbolIndex): String? {
        val file = sym.containingFile ?: return null
        val pkg = NsAliasResolver.resolveAliases(file)[symbolName] ?: return null
        val exportKey = when {
            index.isKnownNpmExport(pkg, "default") -> "default"
            index.isKnownNpmExport(pkg, symbolName) -> symbolName
            else -> index.npmExportNames(pkg).firstOrNull()
        } ?: return null
        return buildDoc("$symbolName (export '$exportKey' from $pkg)", null)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildFnSig(name: String, member: JsMember): String {
        val sig = buildParamsSig(member)
        return "$name($sig): ${member.returns}"
    }

    private fun buildParamsSig(member: JsMember): String =
        member.params.joinToString(", ") { p ->
            when {
                p.rest     -> "...${p.name}: ${p.type}"
                p.optional -> "${p.name}?: ${p.type}"
                else       -> "${p.name}: ${p.type}"
            }
        }

    private fun buildDoc(definition: String, doc: String?): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        append(definition)
        append(DocumentationMarkup.DEFINITION_END)
        if (!doc.isNullOrBlank()) {
            val formatted = JsDocHtmlFormatter.format(doc) ?: doc
            append(DocumentationMarkup.CONTENT_START)
            append(formatted)
            append(DocumentationMarkup.CONTENT_END)
        }
    }
}
