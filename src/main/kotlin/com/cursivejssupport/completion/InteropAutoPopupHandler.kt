package com.cursivejssupport.completion

import com.cursivejssupport.npm.NsAliasResolver
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiFile
import kotlin.math.min

/**
 * Trigger the completion popup as soon as the user enters an interop context.
 *
 * The decision is driven by [InteropContextDetector] over the live document so the popup and
 * the contributor agree on whether the caret is in an interop slot. We register interest in
 * the same trigger characters Cursive itself uses (`.`, `/`, `(`, `"`, `[`, `-`, ` `) so we
 * can pop the lookup the moment the user enters a recognizable form.
 */
class InteropAutoPopupHandler : TypedHandlerDelegate() {

    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (!isClojureDialectFile(file)) return Result.CONTINUE
        if (charTyped !in TRIGGER_CHARS) return Result.CONTINUE

        AutoPopupController.getInstance(project).scheduleAutoPopup(
            editor,
            Condition<PsiFile> { committed ->
                val doc = editor.document
                val caret = min(editor.caretModel.offset, doc.textLength)
                val aliases = NsAliasResolver.resolveAliases(committed)
                val ctx = InteropContextDetector.detect(doc.charsSequence, caret, aliases)
                ctx !is InteropCompletionContext.None
            },
        )
        return Result.CONTINUE
    }

    private fun isClojureDialectFile(file: PsiFile): Boolean {
        val n = file.name
        return n.endsWith(".cljs", ignoreCase = true) ||
            n.endsWith(".cljc", ignoreCase = true) ||
            n.endsWith(".clj", ignoreCase = true)
    }

    companion object {
        /**
         * The characters that *could* place the caret inside an interop slot. We delegate the
         * actual decision to [InteropContextDetector] inside the auto-popup condition.
         */
        val TRIGGER_CHARS: Set<Char> = setOf('.', '/', '(', '"', '[', '-', ' ', '{')

        /**
         * Pure helper: does the document at [caret] look like an interop completion slot?
         * Used by [InteropAutoPopupHandlerTest].
         */
        fun shouldOpen(doc: CharSequence, caret: Int, aliases: Map<String, String> = emptyMap()): Boolean =
            InteropContextDetector.detect(doc, caret, aliases) !is InteropCompletionContext.None
    }
}
