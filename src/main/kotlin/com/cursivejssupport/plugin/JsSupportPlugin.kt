package com.cursivejssupport.plugin

import com.cursivejssupport.index.JsIndexCoordinator
import com.cursivejssupport.npm.JsNpmDependencyWatcher
import com.cursivejssupport.project.CljsProjectModel
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class JsSupportPlugin : ProjectActivity {
    override suspend fun execute(project: Project) {
        JsNpmDependencyWatcher.getInstance(project)
        val model = CljsProjectModel.getInstance(project)
        model.refreshProfiles()
        project.service<JsIndexCoordinator>().start()
    }
}
