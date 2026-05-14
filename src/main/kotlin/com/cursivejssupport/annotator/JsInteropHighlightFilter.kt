package com.cursivejssupport.annotator

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.NsAliasResolver
import com.cursivejssupport.util.JsInteropIndexQueries
import com.cursivejssupport.util.JsResolveUtil
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import cursive.psi.api.ClList
import cursive.psi.impl.symbols.ClEditorSymbol

class JsInteropHighlightFilter : HighlightInfoFilter {

    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null) return true

        val fileName = file.name
        if (!(fileName.endsWith(".cljs") || fileName.endsWith(".cljc") || fileName.endsWith(".clj"))) {
            return true
        }

        val description = highlightInfo.description ?: ""

        val isTypo = isTypoHighlight(highlightInfo, description)
        val isUnresolved = isUnresolvedReferenceHighlight(highlightInfo, description)
        if (!isTypo && !isUnresolved) {
            return true
        }

        val start = highlightInfo.startOffset
        val end = highlightInfo.endOffset
        val document = file.viewProvider.document ?: return true

        // Sanity check the offsets
        if (start < 0 || end > document.textLength || start >= end) {
            return true
        }

        if (isTypo) {
            return !shouldSuppressTypoForInterop(file, document, start, end)
        }

        val text = document.getText(TextRange(start, end))

        val index = JsSymbolIndex.getInstance()
        val aliases = NsAliasResolver.resolveAliases(file)

        // 1. Base namespaces are always valid
        if (text == "js" || text == "cljs" || text == "shadow") {
            return false
        }

        // 2. Global access and dotted `js/` chains (e.g. js/document.createRange)
        if (text.startsWith("js/")) {
            val rest = text.removePrefix("js/")
            if (!rest.contains('.')) {
                if (index.isKnownGlobal(rest)) return false
                return true
            }
            if (JsInteropIndexQueries.isResolvableDottedJsRest(index, rest)) {
                return false
            }
            return true
        }

        // 3. If the text is an alias defined in the ns form suppress it!
        if (aliases.containsKey(text)) {
            return false
        }

        // 3b. Enclosing symbol: dotted `js/` / `namespace == js` chains and bare npm default aliases
        if (index.isLoaded) {
            val elementAtOffset = file.findElementAt(start)
            val enclosing = PsiTreeUtil.getParentOfType(elementAtOffset, ClEditorSymbol::class.java, false)
            if (enclosing != null) {
                val full = enclosing.text?.trim().orEmpty()
                if (full.isNotEmpty() && aliases.containsKey(full)) {
                    return false
                }
                if (full.isNotEmpty() && JsInteropIndexQueries.isResolvableJsInteropSymbol(index, full, enclosing.namespace, enclosing.name)) {
                    return false
                }
            }
        }

        // 4. Member access (e.g., .setStart, .-className)
        if (text.startsWith(".") || text.startsWith(".-")) {
            val memberName = text.removePrefix(".-").removePrefix(".")

            // Find the actual PSI element at the error location
            val elementAtOffset = file.findElementAt(highlightInfo.startOffset)
            val symbol = PsiTreeUtil.getParentOfType(elementAtOffset, ClEditorSymbol::class.java, false)

            if (symbol != null) {
                // Try to find the receiver (e.g., `document` in `(.setStart document ...)`)
                val parentList = symbol.parent as? ClList
                if (parentList != null) {
                    // In a ClList, the first element is usually the method, the second is the receiver
                    val children = parentList.children.filter { it.text != "(" && it.text != ")" && it.text.isNotBlank() }
                    if (children.firstOrNull() == symbol && children.size > 1) {
                        val receiver = children[1]

                        // Use your utility to infer the type!
                        val receiverType = JsResolveUtil.resolveType(receiver, index)

                        if (receiverType != null) {
                            val iface = index.resolveInterface(receiverType)
                            if (iface != null && iface.members.containsKey(memberName)) {
                                return false // Suppress: Known member on a known inferred type!
                            }
                        }
                    }
                }
            }

            // 5. Fallback for dynamic ClojureScript
            // If we couldn't infer the type (very common in CLJS without type hints),
            // we check if this member exists on ANY known interface in your index.
            // If it exists somewhere in the JS ecosystem, we suppress the error to avoid false positives.
            val existsAnywhere = index.hasMemberName(memberName)
            if (existsAnywhere) {
                return false // Suppress: We don't know the type, but the method exists in JS.
            }
        }

        // If we get here, it's not in your index at all. Let Cursive highlight it as a typo!
        return true
    }

    private fun isUnresolvedReferenceHighlight(highlightInfo: HighlightInfo, description: String): Boolean {
        val inspectionId = highlightInfo.inspectionToolId
        if (!inspectionId.isNullOrBlank()) {
            val id = inspectionId.lowercase()
            if (id.contains("unresolved") || id.contains("unknown symbol")) {
                return true
            }
        }
        val d = description.lowercase()
        return d.contains("cannot be resolved") ||
            d.contains("cannot resolve") ||
            d.contains("unresolved symbol")
    }

    /**
     * Spellchecker (`SpellCheckingInspection`) raises typo highlights inside the
     * camel-cased member names of `js/document.createRange`, `.startContainer`, etc.
     * That makes IntelliJ's [com.intellij.spellchecker.quickfixes.RenameTo] quickfix
     * compute suggestions over a TextRange that doesn't match the highlighted text,
     * which throws `StringIndexOutOfBoundsException` in [com.intellij.openapi.util.TextRange.replace]
     * and tanks the highlighting pass (and as a side effect: our completion popup).
     *
     * Suppress typos inside any symbol we recognize as a JS interop form.
     */
    private fun isTypoHighlight(highlightInfo: HighlightInfo, description: String): Boolean =
        isTypoMetadata(highlightInfo.severity.name, highlightInfo.inspectionToolId, description)

    private fun shouldSuppressTypoForInterop(
        file: PsiFile,
        document: Document,
        start: Int,
        end: Int,
    ): Boolean {
        val element = file.findElementAt(start) ?: return false
        val symbol = PsiTreeUtil.getParentOfType(element, ClEditorSymbol::class.java, false)
        val text = document.getText(TextRange(start, end))

        // 1. Bare base namespaces.
        if (text == "js" || text == "cljs" || text == "shadow") return true

        // 2. Full symbol patterns: `js/Foo.bar`, namespace==js, dot/dash members.
        if (symbol != null) {
            val full = symbol.text?.trim().orEmpty()
            if (symbol.namespace == "js") return true
            if (full.startsWith("js/")) return true
            if (full.startsWith(".") || full.startsWith(".-")) return true

            val aliases = NsAliasResolver.resolveAliases(file)
            val ns = symbol.namespace
            if (ns != null && aliases.containsKey(ns)) return true
            if (full.isNotEmpty() && aliases.containsKey(full)) return true
        }

        // 3. Cursive sometimes splits `js`, `/`, `Foo.bar`: the leaf at `start`
        // may itself be the `Foo.bar` token without an enclosing js-symbol.
        if (Companion.looksLikeJsTrailingMemberInSequence(document.charsSequence, start)) return true

        return false
    }

    companion object {
        /** Pure: does `(severityName, inspectionId, description)` correspond to a spellchecker typo highlight? */
        internal fun isTypoMetadata(severityName: String?, inspectionId: String?, description: String): Boolean {
            if (!severityName.isNullOrBlank() && severityName.equals("TYPO", ignoreCase = true)) return true
            if (!inspectionId.isNullOrBlank() && inspectionId.contains("Spell", ignoreCase = true)) return true
            if (description.startsWith("Typo:", ignoreCase = true)) return true
            return false
        }

        /**
         * Pure: looking backwards from [start], do we see a `js/` token (i.e. the highlighted leaf
         * is the trailing member token of `js/<member>` that Cursive split into separate PSI children,
         * possibly through a dotted chain like `js/document.createRange`)?
         */
        internal fun looksLikeJsTrailingMemberInSequence(seq: CharSequence, start: Int): Boolean {
            if (start <= 0 || start > seq.length) return false
            var i = start - 1
            // Walk back through the dotted identifier chain: letters, digits, `.`, `_`, `$`, `-`.
            while (i >= 0) {
                val c = seq[i]
                if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '$' || c == '-') {
                    i--
                    continue
                }
                break
            }
            if (i < 0 || seq[i] != '/') return false
            i--
            if (i < 0) return false
            if (i < 1) {
                // seq starts with `s/` (only one char before `/`); not a js token.
                return false
            }
            return seq[i] == 's' && seq[i - 1] == 'j' &&
                (i - 1 == 0 || !seq[i - 2].isLetterOrDigit())
        }
    }
}