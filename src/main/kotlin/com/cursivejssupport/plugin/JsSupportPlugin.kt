package com.cursivejssupport.plugin

import com.cursivejssupport.index.JsIndexLoader
import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.npm.JsNpmDependencyWatcher
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.*

class JsSupportPlugin : ProjectActivity {

    private val log = logger<JsSupportPlugin>()

    override suspend fun execute(project: Project) {
        JsNpmDependencyWatcher.getInstance(project)

        val index = JsSymbolIndex.getInstance()

        if (!index.claimLoad()) return

        index.setLoaded(false)

        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()

            try {
                JsIndexLoader.loadBundledBrowser(index)
                log.info("Cursive JS Support: bundled browser index loaded (${System.currentTimeMillis() - t0}ms)")
            } catch (e: Exception) {
                log.error("Cursive JS Support: failed to load bundled browser index", e)
            }

            try {
                JsIndexLoader.loadNpmPackages(project, index)
            } catch (e: Exception) {
                log.error("Cursive JS Support: npm indexing failed", e)
            }

            index.setLoaded(true)
            log.info("Cursive JS Support: initialization complete (${System.currentTimeMillis() - t0}ms)")
        }
    }
}
