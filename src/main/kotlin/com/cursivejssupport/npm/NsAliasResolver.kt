package com.cursivejssupport.npm

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import java.util.concurrent.ConcurrentHashMap

object NsAliasResolver {

    private val aliasCache = ConcurrentHashMap<String, Map<String, NpmBinding>>()

    fun resolveAliases(file: PsiFile): Map<String, NpmBinding> {
        val key = "${file.virtualFile?.path ?: file.name}:${file.modificationStamp}"
        return aliasCache.computeIfAbsent(key) { computeAliases(file) }
    }

    fun resolveAliasDeclaration(file: PsiFile, targetAlias: String): PsiElement? {
        val nsForm = findNsForm(file) ?: return null
        for (requireForm in findAllRequireForms(nsForm)) {
            resolveAliasDeclarationInRequire(requireForm, targetAlias)?.let { return it }
        }
        return null
    }

    private fun computeAliases(file: PsiFile): Map<String, NpmBinding> {
        val nsForm = findNsForm(file) ?: return emptyMap()
        val merged = LinkedHashMap<String, NpmBinding>()
        for (requireForm in findAllRequireForms(nsForm)) {
            merged.putAll(extractAliases(requireForm, file))
        }
        for (importForm in findAllImportForms(nsForm)) {
            merged.putAll(extractImportAliases(importForm))
        }
        return merged
    }

    private fun resolveAliasDeclarationInRequire(requireForm: PsiElement, targetAlias: String): PsiElement? {
        requireForm.children.forEach { spec ->
            if (spec.text.startsWith("[")) {
                val items = spec.children.filter {
                    it !is PsiWhiteSpace &&
                        it !is PsiComment &&
                        it.text != "[" &&
                        it.text != "]" &&
                        it.text != "(" &&
                        it.text != ")"
                }

                var i = 1
                while (i < items.size) {
                    val text = items[i].text
                    if (text == ":as" || text == ":default" || text == ":all") {
                        if (i + 1 < items.size) {
                            val aliasItem = items[i + 1]
                            if (aliasItem.text == targetAlias) {
                                return aliasItem
                            }
                        }
                        i += 2
                        continue
                    } else if (text == ":refer") {
                        if (i + 1 < items.size) {
                            val referCollection = items[i + 1]
                            if (referCollection.text.startsWith("[") || referCollection.text.startsWith("(")) {
                                referCollection.children.forEach { child ->
                                    if (child.text == targetAlias) {
                                        return child
                                    }
                                }
                            }
                        }
                        i += 2
                        continue
                    } else if (text == ":rename") {
                        if (i + 1 < items.size) {
                            val renameCollection = items[i + 1]
                            findSymbolInRenameMap(renameCollection, targetAlias)?.let { return it }
                        }
                        i += 2
                        continue
                    }
                    i++
                }
            }
        }
        return null
    }

    private fun findSymbolInRenameMap(renameCollection: PsiElement, target: String): PsiElement? {
        val kids = renameCollection.children.filter {
            it !is PsiWhiteSpace && it !is PsiComment &&
                it.text != "{" && it.text != "}" &&
                it.text != "[" && it.text != "]" &&
                it.text != "(" && it.text != ")"
        }
        var j = 0
        while (j + 1 < kids.size) {
            val a = kids[j].text
            val b = kids[j + 1].text
            if (a == target || b == target) {
                return kids[j].takeIf { it.text == target } ?: kids[j + 1]
            }
            j += 2
        }
        return null
    }

    private fun findNsForm(file: PsiFile): PsiElement? =
        file.children.firstOrNull { list ->
            if (list.text.startsWith("(")) {
                val first = list.children.firstOrNull { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" }
                first?.text == "ns"
            } else false
        }

    private fun findAllRequireForms(nsForm: PsiElement): List<PsiElement> =
        nsForm.children.filter { list ->
            if (list.text.startsWith("(")) {
                val first = list.children.firstOrNull { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" }
                first?.text == ":require"
            } else false
        }

    private fun findAllImportForms(nsForm: PsiElement): List<PsiElement> =
        nsForm.children.filter { list ->
            if (list.text.startsWith("(")) {
                val first = list.children.firstOrNull { it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" }
                first?.text == ":import"
            } else false
        }

    /**
     * Parses (:import (goog.fx.dom Scroll Slide) goog.events.EventType)
     *
     * Two forms:
     *  - (goog.fx.dom Scroll Slide) → Scroll and Slide become REFER aliases of goog.fx.dom
     *  - goog.events.EventType     → EventType becomes a REFER alias of goog.events
     */
    private fun extractImportAliases(importForm: PsiElement): Map<String, NpmBinding> {
        val aliases = mutableMapOf<String, NpmBinding>()

        val entries = importForm.children.filter {
            it !is PsiWhiteSpace && it !is PsiComment && it.text != "(" && it.text != ")" && it.text != ":import"
        }

        for (entry in entries) {
            val entryText = entry.text.trim()
            if (entryText.startsWith("(")) {
                // (goog.fx.dom Scroll Slide ...)
                val kids = entry.children.filter {
                    it !is PsiWhiteSpace && it !is PsiComment &&
                        it.text != "(" && it.text != ")"
                }
                val ns = kids.firstOrNull()?.text ?: continue
                if (!isGoogNamespace(ns)) continue
                for (kid in kids.drop(1)) {
                    val className = kid.text.trim()
                    if (className.isNotBlank()) {
                        aliases[className] = NpmBinding(ns, NpmBindingKind.REFER, exportName = className)
                    }
                }
            } else if (isGoogNamespace(entryText)) {
                // goog.events.EventType  →  EventType from goog.events
                val lastDot = entryText.lastIndexOf('.')
                if (lastDot > 0) {
                    val ns = entryText.substring(0, lastDot)
                    val className = entryText.substring(lastDot + 1)
                    if (className.isNotBlank()) {
                        aliases[className] = NpmBinding(ns, NpmBindingKind.REFER, exportName = className)
                    }
                }
            }
        }

        return aliases
    }

    private fun isGoogNamespace(text: String): Boolean =
        text == "goog" || text.startsWith("goog.")

    private fun extractAliases(requireForm: PsiElement, file: PsiFile): Map<String, NpmBinding> {
        val aliases = mutableMapOf<String, NpmBinding>()

        requireForm.children.forEach { spec ->
            if (spec.text.startsWith("[")) {
                val items = spec.children.filter {
                    it !is PsiWhiteSpace &&
                        it !is PsiComment &&
                        it.text != "[" &&
                        it.text != "]" &&
                        it.text != "(" &&
                        it.text != ")"
                }

                val firstItem = items.firstOrNull() ?: return@forEach
                val firstText = firstItem.text

                when {
                    firstText.startsWith("\"") -> {
                        val packageName = firstText.trim('"')
                        if (packageName.startsWith(".") || packageName.startsWith("/")) {
                            parseRelativeRequireSpec(items, packageName, file, aliases)
                        } else {
                            parseNpmRequireSpec(items, packageName, aliases)
                        }
                    }
                    isGoogNamespace(firstText) -> parseGoogRequireSpec(items, firstText, aliases)
                    else -> return@forEach
                }
            }
        }

        return aliases
    }

    /**
     * Bind `(:require ["./helper.js" :as helper] …)` and absolute `"/abs/path.js"` forms.
     * The package string is kept verbatim for display; the resolved target file path is stored
     * on the binding so semantic/goto can jump straight to it.
     */
    private fun parseRelativeRequireSpec(
        items: List<PsiElement>,
        requirePath: String,
        file: PsiFile,
        aliases: MutableMap<String, NpmBinding>,
    ) {
        val resolved = RelativeModuleResolver.resolve(file, requirePath)?.path
        var i = 1
        while (i < items.size) {
            val text = items[i].text
            if (text == ":as") {
                if (i + 1 < items.size) {
                    val alias = items[i + 1].text
                    if (alias.isNotBlank()) aliases[alias] = NpmBinding(requirePath, NpmBindingKind.RELATIVE, relativeFilePath = resolved)
                }
                i += 2; continue
            } else if (text == ":default") {
                if (i + 1 < items.size) {
                    val alias = items[i + 1].text
                    if (alias.isNotBlank()) aliases[alias] = NpmBinding(requirePath, NpmBindingKind.RELATIVE, exportName = "default", relativeFilePath = resolved)
                }
                i += 2; continue
            } else if (text == ":all") {
                if (i + 1 < items.size) {
                    val alias = items[i + 1].text
                    if (alias.isNotBlank()) aliases[alias] = NpmBinding(requirePath, NpmBindingKind.RELATIVE, relativeFilePath = resolved)
                }
                i += 2; continue
            } else if (text == ":refer") {
                if (i + 1 < items.size) {
                    val referCollection = items[i + 1]
                    if (referCollection.text.startsWith("[") || referCollection.text.startsWith("(")) {
                        referCollection.children.forEach { child ->
                            if (child !is PsiWhiteSpace && child !is PsiComment &&
                                child.text != "[" && child.text != "]" &&
                                child.text != "(" && child.text != ")") {
                                val refName = child.text
                                if (refName.isNotBlank()) aliases[refName] = NpmBinding(requirePath, NpmBindingKind.RELATIVE, exportName = refName, relativeFilePath = resolved)
                            }
                        }
                    }
                }
                i += 2; continue
            } else if (text == ":rename") {
                if (i + 1 < items.size) applyRelativeRenamePairs(items[i + 1], requirePath, resolved, aliases)
                i += 2; continue
            }
            i++
        }
    }

    private fun applyRelativeRenamePairs(renameCollection: PsiElement, requirePath: String, resolved: String?, aliases: MutableMap<String, NpmBinding>) {
        val kids = renameCollection.children.filter {
            it !is PsiWhiteSpace && it !is PsiComment &&
                it.text != "{" && it.text != "}" &&
                it.text != "[" && it.text != "]" &&
                it.text != "(" && it.text != ")"
        }
        var j = 0
        while (j + 1 < kids.size) {
            val from = kids[j].text.removePrefix(":").trim()
            val to = kids[j + 1].text.removePrefix(":").trim()
            if (to.isNotBlank()) aliases[to] = NpmBinding(requirePath, NpmBindingKind.RELATIVE, exportName = from, relativeFilePath = resolved)
            j += 2
        }
    }

    private fun parseNpmRequireSpec(items: List<PsiElement>, packageName: String, aliases: MutableMap<String, NpmBinding>) {
        var i = 1
        while (i < items.size) {
            val text = items[i].text
            if (text == ":as") {
                if (i + 1 < items.size) {
                    val alias = items[i + 1].text
                    if (alias.isNotBlank()) aliases[alias] = NpmBinding(packageName, NpmBindingKind.AS)
                }
                i += 2; continue
            } else if (text == ":default") {
                if (i + 1 < items.size) {
                    val alias = items[i + 1].text
                    if (alias.isNotBlank()) aliases[alias] = NpmBinding(packageName, NpmBindingKind.DEFAULT)
                }
                i += 2; continue
            } else if (text == ":all") {
                if (i + 1 < items.size) {
                    val alias = items[i + 1].text
                    if (alias.isNotBlank()) aliases[alias] = NpmBinding(packageName, NpmBindingKind.ALL)
                }
                i += 2; continue
            } else if (text == ":refer") {
                if (i + 1 < items.size) {
                    val referCollection = items[i + 1]
                    if (referCollection.text.startsWith("[") || referCollection.text.startsWith("(")) {
                        referCollection.children.forEach { child ->
                            if (child !is PsiWhiteSpace && child !is PsiComment &&
                                child.text != "[" && child.text != "]" &&
                                child.text != "(" && child.text != ")") {
                                val refName = child.text
                                if (refName.isNotBlank()) aliases[refName] = NpmBinding(packageName, NpmBindingKind.REFER, exportName = refName)
                            }
                        }
                    }
                }
                i += 2; continue
            } else if (text == ":rename") {
                if (i + 1 < items.size) applyRenamePairs(items[i + 1], packageName, aliases)
                i += 2; continue
            }
            i++
        }
    }

    private fun parseGoogRequireSpec(items: List<PsiElement>, namespaceName: String, aliases: MutableMap<String, NpmBinding>) {
        var hasAlias = false
        var i = 1
        while (i < items.size) {
            val text = items[i].text
            if (text == ":as" && i + 1 < items.size) {
                val alias = items[i + 1].text
                if (alias.isNotBlank()) {
                    aliases[alias] = NpmBinding(namespaceName, NpmBindingKind.AS)
                    hasAlias = true
                }
                i += 2; continue
            } else if (text == ":refer" && i + 1 < items.size) {
                val col = items[i + 1]
                if (col.text.startsWith("[") || col.text.startsWith("(")) {
                    col.children.filter {
                        it !is PsiWhiteSpace && it !is PsiComment &&
                            it.text !in listOf("[", "]", "(", ")")
                    }.forEach { child ->
                        val n = child.text
                        if (n.isNotBlank()) aliases[n] = NpmBinding(namespaceName, NpmBindingKind.REFER, exportName = n)
                    }
                }
                i += 2; continue
            }
            i++
        }
        // [goog.string] with no :as — register the namespace name itself so direct qualified access resolves
        if (!hasAlias) aliases[namespaceName] = NpmBinding(namespaceName, NpmBindingKind.AS)
    }

    private fun applyRenamePairs(renameCollection: PsiElement, packageName: String, aliases: MutableMap<String, NpmBinding>) {
        val kids = renameCollection.children.filter {
            it !is PsiWhiteSpace && it !is PsiComment &&
                it.text != "{" && it.text != "}" &&
                it.text != "[" && it.text != "]" &&
                it.text != "(" && it.text != ")"
        }
        var j = 0
        while (j + 1 < kids.size) {
            val from = kids[j].text.removePrefix(":").trim()  // original export name
            val to = kids[j + 1].text.removePrefix(":").trim()  // local alias
            // `:rename {original local}` binds only the local name. Keeping the original here
            // produced completion/navigation for a symbol that ClojureScript does not bind.
            if (to.isNotBlank()) aliases[to] = NpmBinding(packageName, NpmBindingKind.REFER, exportName = from)
            j += 2
        }
    }
}
