package com.cursivejssupport.completion

import com.cursivejssupport.debug.InteropLogView
import com.cursivejssupport.util.InteropDebugLog
import com.cursivejssupport.util.JsInteropPsi
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import kotlin.math.max
import kotlin.math.min

/**
 * Auto-popup for JS interop completion is driven here: [CompletionContributor.invokeAutoPopup] is deprecated and is
 * not reliably invoked for characters like `.` (the platform points to [TypedHandlerDelegate.checkAutoPopup]).
 */
class JsInteropCompletionAutoPopupTypedHandler : TypedHandlerDelegate() {

    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (!isClojureDialectFile(file)) return Result.CONTINUE

        when (charTyped) {
            '/' -> {
                val doc = editor.document
                val offset = min(editor.caretModel.offset, doc.textLength)
                InteropCompletionIntentStore.remember(editor, '/', offset)
                if (InteropCompletionIntentParser.shouldSuppressInvalidSlash(doc.charsSequence, offset, '/')) {
                    InteropDebugLog.debug(
                        "[interop-completion] typedHandler.checkAutoPopup: skip schedule for invalid js/.../ (file=${file.name} caret=$offset)",
                    )
                    return Result.CONTINUE
                }
                InteropLogView.noteProject(project)
                InteropDebugLog.info(
                    "[interop-completion] typedHandler.checkAutoPopup file=${file.name} char='/' caret=$offset",
                )
                scheduleJsInteropAutoPopup(project, editor, '/')
                return Result.CONTINUE
            }
            '.' -> {
                InteropLogView.noteProject(project)
                InteropCompletionIntentStore.remember(editor, '.', editor.caretModel.offset)
                InteropDebugLog.info(
                    "[interop-completion] typedHandler.checkAutoPopup file=${file.name} char='.' caret=${editor.caretModel.offset}",
                )
                scheduleJsInteropAutoPopup(project, editor, '.')
                return Result.CONTINUE
            }
            '(' -> {
                InteropLogView.noteProject(project)
                InteropDebugLog.info(
                    "[interop-completion] typedHandler.checkAutoPopup file=${file.name} char='(' caret=${editor.caretModel.offset}",
                )
                val ed = editor
                AutoPopupController.getInstance(project).scheduleAutoPopup(
                    ed,
                    Condition<PsiFile> { committed ->
                        val doc = ed.document
                        val offset = min(ed.caretModel.offset, doc.textLength)
                        if (offset <= 0) return@Condition false
                        val el = committed.findElementAt(offset - 1) ?: return@Condition false
                        JsInteropPsi.enclosingEditorSymbol(el)?.namespace == "js"
                    },
                )
                return Result.CONTINUE
            }
            '-' -> {
                val ed = editor
                val clojureName = file.name
                AutoPopupController.getInstance(project).scheduleAutoPopup(
                    ed,
                    Condition<PsiFile> { committed ->
                        val doc = ed.document
                        val offset = min(ed.caretModel.offset, doc.textLength)
                        if (offset <= 0 || doc.charsSequence[offset - 1] != '.') return@Condition false
                        if (offset >= 2 && doc.charsSequence[offset - 2] == '.') return@Condition false
                        if (!clojureName.endsWith(".cljs") && !clojureName.endsWith(".cljc") && !clojureName.endsWith(".clj")) {
                            return@Condition false
                        }
                        val el = committed.findElementAt(offset - 1) ?: return@Condition false
                        val logical = JsInteropPsi.completionLogicalText(el, el.text.trim())
                        logical.startsWith(".") || logical.startsWith("js/") || logical.contains("js/")
                    },
                )
                return Result.CONTINUE
            }
        }
        return Result.CONTINUE
    }

    private fun scheduleJsInteropAutoPopup(project: Project, editor: Editor, typedChar: Char? = null) {
        val ed = editor
        AutoPopupController.getInstance(project).scheduleAutoPopup(
            ed,
            Condition<PsiFile> { committed ->
                val doc = ed.document
                val offset = min(ed.caretModel.offset, doc.textLength)
                if (offset < 0) return@Condition false
                if (typedChar == '/' && InteropCompletionIntentParser.shouldSuppressInvalidSlash(doc.charsSequence, offset, typedChar)) {
                    return@Condition false
                }
                val start = max(0, offset - 400)
                val slice = doc.getText(TextRange(start, offset))
                if (typedChar == '.' && slice.lastIndexOf("js/") >= 0) return@Condition true
                if (typedChar != '/' && (slice.contains("js/") || slice.contains("(js/"))) return@Condition true
                val probe = offset.coerceAtMost(committed.textLength).let { if (it > 0) it - 1 else 0 }
                val el = committed.findElementAt(probe) ?: return@Condition false
                JsInteropPsi.enclosingEditorSymbol(el)?.namespace == "js"
            },
        )
    }

    private fun isClojureDialectFile(file: PsiFile): Boolean {
        val n = file.name
        return n.endsWith(".cljs", ignoreCase = true) ||
            n.endsWith(".cljc", ignoreCase = true) ||
            n.endsWith(".clj", ignoreCase = true)
    }
}
