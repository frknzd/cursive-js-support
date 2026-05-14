package com.cursivejssupport.npm

import com.cursivejssupport.index.JsIndexLoader
import com.cursivejssupport.index.JsSymbolIndex
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * Debounced reload of npm typings when dependency manifests change.
 */
class JsNpmDependencyWatcher(private val project: Project) : Disposable {

    private val log = logger<JsNpmDependencyWatcher>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val connection = project.messageBus.connect(this)

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (project.isDisposed) return
                if (events.any { isDependencyRelated(it) }) {
                    scheduleReload()
                }
            }
        })
    }

    private fun isDependencyRelated(event: VFileEvent): Boolean {
        val path = (event.file?.path ?: return false).lowercase()
        if (path.endsWith("package.json")) return true
        if (path.endsWith("shadow-cljs.edn")) return true
        if (path.endsWith("package-lock.json")) return true
        if (path.endsWith("yarn.lock")) return true
        if (path.endsWith("pnpm-lock.yaml")) return true
        if (path.contains("node_modules")) return true
        return false
    }

    private fun scheduleReload() {
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (project.isDisposed) return@addRequest
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    JsIndexLoader.loadNpmPackages(project, JsSymbolIndex.getInstance())
                } catch (e: Exception) {
                    log.warn("Cursive JS Support: npm re-index failed", e)
                }
            }
        }, 1500)
    }

    override fun dispose() {
        alarm.dispose()
        connection.disconnect()
    }

    companion object {
        fun getInstance(project: Project): JsNpmDependencyWatcher = project.service()
    }
}
