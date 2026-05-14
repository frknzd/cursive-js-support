package com.cursivejssupport.debug

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Materializes the JsInteropLog tool window once per project so [InteropLogView.attach] runs
 * before the first log line (and avoids modality blocking during completion).
 */
class InteropLogWarmupActivity : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        InteropLogView.noteProject(project)
        ApplicationManager.getApplication().invokeLater(
            {
                InteropLogView.ensureToolWindowCreated(project)
            },
            ModalityState.any(),
        )
    }
}
