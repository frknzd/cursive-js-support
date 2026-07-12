package com.cursivejssupport.project

import com.cursivejssupport.npm.JsNpmDependencyWatcher
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.FormBuilder
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

class CljsStatusToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = StatusPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel.component, "Status", false))
    }
}

private class StatusPanel(private val project: Project) {
    private val text = JBTextArea().apply { isEditable = false; lineWrap = false }
    private val refresh = JButton("Refresh profiles").apply { addActionListener { model().refreshProfiles(); render() } }
    private val reindex = JButton("Reindex JavaScript typings").apply {
        addActionListener { JsNpmDependencyWatcher.getInstance(project).reloadNow(); render() }
    }
    private val copy = JButton("Copy diagnostics").apply {
        addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(this@StatusPanel.text.text)) }
    }
    val component: JComponent = FormBuilder.createFormBuilder()
        .addComponent(JBScrollPane(text))
        .addComponent(JPanel().apply { add(refresh); add(reindex); add(copy) })
        .panel
    private val timer = Timer(2_000) { render() }.apply { start() }

    init { render() }

    private fun model() = CljsProjectModel.getInstance(project)

    private fun render() {
        if (project.isDisposed) { timer.stop(); return }
        val model = model()
        val snapshot = model.indexSnapshot
        text.text = buildString {
            appendLine("Index: ${snapshot.status.phase}")
            appendLine("Generation: ${snapshot.generation}")
            appendLine("Indexed npm packages: ${snapshot.status.packageCount}")
            appendLine("Last refresh: ${snapshot.status.lastRefresh ?: "never"}")
            appendLine("Type source: IntelliJ JavaScript + bundled TypeScript declarations")
            if (snapshot.status.warnings.isNotEmpty()) {
                appendLine("Warnings:")
                snapshot.status.warnings.forEach { appendLine("  - $it") }
            }
            appendLine()
            appendLine("Build profiles (${model.profiles.size}):")
            model.profiles.forEach { profile ->
                appendLine("  ${profile.id}: ${profile.target} via ${profile.tool}")
                appendLine("    command: ${profile.launchCommand.joinToString(" ")}")
                profile.outputDirectory?.let { appendLine("    output: $it") }
                profile.browserUrl?.let { appendLine("    URL: $it") }
            }
        }
    }
}
