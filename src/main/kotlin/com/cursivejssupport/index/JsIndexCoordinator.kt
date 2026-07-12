package com.cursivejssupport.index

import com.cursivejssupport.project.CljsProjectModel
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class JsIndexCoordinator(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val log = logger<JsIndexCoordinator>()

    fun start() {
        val index = JsSymbolIndex.getInstance(project)
        if (!index.claimLoad()) return
        val model = CljsProjectModel.getInstance(project)
        coroutineScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val replacement = JsSymbolIndex()
            model.markIndexing()
            try {
                JsIndexLoader.loadBundledBrowser(replacement)
                JsIndexLoader.loadBundledGoog(replacement)
                JsIndexLoader.loadNpmPackages(project, replacement)
                index.publish(replacement)
                model.markReady(replacement.indexedNpmPackageCount())
                log.info("Cursive JS Support: initialization complete (${System.currentTimeMillis() - started}ms)")
            } catch (e: Exception) {
                index.finishLoadFailure()
                model.markFailed(e.message ?: e.javaClass.simpleName)
                log.error("Cursive JS Support: indexing failed; keeping the previous snapshot", e)
            }
        }
    }
}
