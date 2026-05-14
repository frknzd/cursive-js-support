package com.cursivejssupport.debug

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Visible log sink: **View | Tool Windows | JsInteropLog**.
 *
 * Log lines are flushed with [ModalityState.any] so they still run while the completion popup is open
 * (the default modality would otherwise defer [invokeLater] until the popup closes).
 */
object InteropLogView {

    private val log = logger<InteropLogView>()

    private const val MAX_CHARS = 400_000

    private val pending = ConcurrentLinkedQueue<String>()

    @Volatile
    private var toolWindow: ToolWindow? = null

    @Volatile
    private var textArea: JBTextArea? = null

    @Volatile
    private var lastProject: Project? = null

    fun noteProject(project: Project) {
        if (!project.isDisposed) {
            lastProject = project
        }
    }

    fun ensureToolWindowCreated(project: Project) {
        if (project.isDisposed) return
        noteProject(project)
        val w = ToolWindowManager.getInstance(project).getToolWindow(InteropLogToolWindowFactory.TOOL_WINDOW_ID)
        if (w == null) {
            log.error(
                "JsInteropLog tool window missing for project '${project.name}'. " +
                    "Expected id='${InteropLogToolWindowFactory.TOOL_WINDOW_ID}' in plugin.xml.",
            )
            return
        }
        w.show(null)
    }

    fun attach(window: ToolWindow, area: JBTextArea) {
        toolWindow = window
        textArea = area
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true
        val monoSize = UIUtil.getLabelFont().size
        area.font = Font(Font.MONOSPACED, Font.PLAIN, monoSize)
        area.emptyText.text = "Interop go-to / completion logs appear here."
        drainPendingTo(area, window)
    }

    fun appendLine(message: String) {
        val app = ApplicationManager.getApplication() ?: return
        if (app.isDisposed) return
        pending.add(message)
        app.invokeLater(
            { flushOrScheduleShow() },
            ModalityState.any(),
        )
    }

    private fun resolveProject(): Project? {
        val p = lastProject
        if (p != null && !p.isDisposed) return p
        return ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
    }

    private fun flushOrScheduleShow() {
        val ta = textArea
        val tw = toolWindow
        if (ta != null && tw != null) {
            drainPendingTo(ta, tw)
            return
        }
        val project = resolveProject() ?: run {
            spillPendingToFileLog("InteropLogView: no Project (call InteropLogView.noteProject from editor paths)")
            return
        }
        val w = ToolWindowManager.getInstance(project).getToolWindow(InteropLogToolWindowFactory.TOOL_WINDOW_ID)
        if (w == null) {
            spillPendingToFileLog(
                "InteropLogView: ToolWindowManager.getToolWindow('${InteropLogToolWindowFactory.TOOL_WINDOW_ID}') null",
            )
            return
        }
        w.show {
            ApplicationManager.getApplication().invokeLater(
                { retryDrainAfterShow(0) },
                ModalityState.any(),
            )
        }
    }

    private fun retryDrainAfterShow(attempt: Int) {
        val area = textArea
        val win = toolWindow
        if (area != null && win != null) {
            drainPendingTo(area, win)
            return
        }
        if (attempt < 25) {
            ApplicationManager.getApplication().invokeLater(
                { retryDrainAfterShow(attempt + 1) },
                ModalityState.any(),
            )
            return
        }
        spillPendingToFileLog("InteropLogView: attach() never ran after ToolWindow.show (attempts=$attempt)")
    }

    private fun spillPendingToFileLog(reason: String) {
        log.warn(reason)
        while (true) {
            val line = pending.poll() ?: break
            log.info("[interop-log-fallback] $line")
        }
    }

    private fun drainPendingTo(ta: JBTextArea, tw: ToolWindow) {
        while (true) {
            val line = pending.poll() ?: break
            ta.append(line)
            ta.append("\n")
        }
        trimIfNeeded(ta)
        runCatching { ta.caretPosition = ta.document.length }
        if (!tw.isVisible) {
            tw.show(null)
        }
    }

    private fun trimIfNeeded(ta: JBTextArea) {
        val doc = ta.document
        val len = doc.length
        if (len > MAX_CHARS) {
            runCatching {
                doc.remove(0, len - MAX_CHARS / 2)
            }
        }
    }
}
