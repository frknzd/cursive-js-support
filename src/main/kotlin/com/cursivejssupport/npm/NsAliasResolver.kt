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
            merged.putAll(extractAliases(requireForm))
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

    private fun extractAliases(requireForm: PsiElement): Map<String, NpmBinding> {
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

                if (!firstText.startsWith("\"")) return@forEach

                val packageName = firstText.trim('"')
                if (packageName.startsWith(".") || packageName.startsWith("/")) return@forEach

                var i = 1
                while (i < items.size) {
                    val text = items[i].text

                    if (text == ":as") {
                        if (i + 1 < items.size) {
                            val alias = items[i + 1].text
                            if (alias.isNotBlank()) {
                                aliases[alias] = NpmBinding(packageName, NpmBindingKind.AS)
                            }
                        }
                        i += 2
                        continue
                    } else if (text == ":default") {
                        if (i + 1 < items.size) {
                            val alias = items[i + 1].text
                            if (alias.isNotBlank()) {
                                aliases[alias] = NpmBinding(packageName, NpmBindingKind.DEFAULT)
                            }
                        }
                        i += 2
                        continue
                    } else if (text == ":all") {
                        if (i + 1 < items.size) {
                            val alias = items[i + 1].text
                            if (alias.isNotBlank()) {
                                aliases[alias] = NpmBinding(packageName, NpmBindingKind.ALL)
                            }
                        }
                        i += 2
                        continue
                    } else if (text == ":refer") {
                        if (i + 1 < items.size) {
                            val referCollection = items[i + 1]
                            if (referCollection.text.startsWith("[") || referCollection.text.startsWith("(")) {
                                referCollection.children.forEach { child ->
                                    if (child !is PsiWhiteSpace && child !is PsiComment &&
                                        child.text != "[" && child.text != "]" &&
                                        child.text != "(" && child.text != ")") {

                                        val refName = child.text
                                        if (refName.isNotBlank()) {
                                            aliases[refName] = NpmBinding(packageName, NpmBindingKind.REFER, exportName = refName)
                                        }
                                    }
                                }
                            }
                        }
                        i += 2
                        continue
                    } else if (text == ":rename") {
                        if (i + 1 < items.size) {
                            applyRenamePairs(items[i + 1], packageName, aliases)
                        }
                        i += 2
                        continue
                    }
                    i++
                }
            }
        }

        return aliases
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
            if (from.isNotBlank()) aliases[from] = NpmBinding(packageName, NpmBindingKind.REFER, exportName = from)
            if (to.isNotBlank()) aliases[to] = NpmBinding(packageName, NpmBindingKind.REFER, exportName = from)
            j += 2
        }
    }
}
