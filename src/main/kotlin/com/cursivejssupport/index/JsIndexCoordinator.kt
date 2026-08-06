package com.cursivejssupport.index

import com.cursivejssupport.project.CljsProjectModel
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
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
                JsIndexLoader.loadAllBundled(replacement)

                // The npm stage is the one that reaches outside the plugin — the project's own
                // build files, Node, node_modules — so it is also the one that fails. The bundled
                // browser/Node/Bun/Deno symbols are already built at this point and cost the user
                // nothing to keep, so they are published either way: a project whose npm typings
                // cannot be read still gets `js/*` interop rather than a plugin that does nothing.
                val npmFailure = runCatching { JsIndexLoader.loadNpmPackages(project, replacement) }
                    .exceptionOrNull()
                    ?.also { if (it is CancellationException) throw it }

                index.publish(replacement)
                if (npmFailure == null) {
                    model.markReady(replacement.indexedNpmPackageCount())
                    log.info("Cursive JS Support: initialization complete (${System.currentTimeMillis() - started}ms)")
                } else {
                    model.markFailed(describe(npmFailure))
                    log.warn("Cursive JS Support: npm typings unavailable; bundled symbols are still indexed", npmFailure)
                }
            } catch (e: CancellationException) {
                index.finishLoadFailure()
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: a mis-installed plugin (the composed jar without the
                // libraries that ship beside it) throws NoClassDefFoundError, and letting that
                // escape leaves `claimLoad` held for the rest of the session — the index never
                // retries and every interop symbol stays unresolved.
                index.finishLoadFailure()
                model.markFailed(describe(e))
                log.error("Cursive JS Support: indexing failed; keeping the previous snapshot", e)
            }
        }
    }

    /**
     * What the status tool window shows. A bare `message` is unhelpful for the linkage errors a
     * mis-installed plugin raises — `NoClassDefFoundError` carries only the missing class name —
     * so the type is always part of the text.
     */
    private fun describe(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() }?.let { "${e.javaClass.simpleName}: $it" }
            ?: e.javaClass.simpleName
}
