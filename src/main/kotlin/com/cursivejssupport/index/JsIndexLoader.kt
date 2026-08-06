package com.cursivejssupport.index

import com.cursivejssupport.npm.NpmPackageResolver
import com.cursivejssupport.parser.DtsParser
import com.cursivejssupport.parser.ParsedSymbols
import com.cursivejssupport.parser.withSyntheticGlobalThis
import com.cursivejssupport.settings.JsSupportSettings
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.util.zip.GZIPInputStream

object JsIndexLoader {

    private val log = logger<JsIndexLoader>()
    private val mapper = jacksonObjectMapper()

    /**
     * The configured browser index, falling back to the shipped default outside a running IDE —
     * the settings service needs an Application, which plain JUnit tests of the bundled indexes
     * don't have.
     */
    private fun browserSymbolsResourcePath(): String =
        if (ApplicationManager.getApplication() == null) JsSupportSettings.State().browserSymbolsResourcePath
        else JsSupportSettings.getInstance().state.browserSymbolsResourcePath

    fun loadBundledBrowser(index: JsSymbolIndex) {
        val resourcePath = browserSymbolsResourcePath()
        val stream = JsIndexLoader::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Pre-calculated index resource not found: $resourcePath")

        GZIPInputStream(stream).use { gz ->
            val symbols = mapper.readValue<ParsedSymbols>(gz)
            index.load(symbols.withSyntheticGlobalThis())
        }
    }

    fun loadBundledGoog(index: JsSymbolIndex) {
        val stream = JsIndexLoader::class.java.getResourceAsStream("/js/goog-symbols.json.gz")
            ?: run { log.warn("Cursive JS Support: goog-symbols.json.gz not found — skipping goog namespace index"); return }
        GZIPInputStream(stream).use { gz ->
            val tree = mapper.readTree(gz)
            tree.fields().forEach { (namespaceName, symbolsNode) ->
                runCatching {
                    index.loadNpmPackage(namespaceName, mapper.treeToValue(symbolsNode, ParsedSymbols::class.java))
                }.onFailure { log.warn("Cursive JS Support: skipping goog namespace $namespaceName — ${it.message}") }
            }
        }
    }

    /**
     * Load a bundled environment index (Node / Bun / Deno). These are pre-generated from
     * `@types/node`, `@types/bun`, and `lib.deno.ns.d.ts`; ambient external modules are scoped
     * into [ParsedSymbols.modules] and registered as packages, while their top-level globals
     * (e.g. `process`, `Bun`, `Deno`) carry the matching [com.cursivejssupport.index.JsEnvironment]
     * so per-file runtime-target filtering can hide them outside their environment.
     */
    private fun loadBundledEnvironment(index: JsSymbolIndex, resource: String, label: String) {
        val stream = JsIndexLoader::class.java.getResourceAsStream(resource)
            ?: run { log.warn("Cursive JS Support: $resource not found — skipping $label index"); return }
        GZIPInputStream(stream).use { gz ->
            val symbols = mapper.readValue<ParsedSymbols>(gz)
            index.load(symbols)
        }
    }

    fun loadBundledNode(index: JsSymbolIndex) = loadBundledEnvironment(index, "/js/node-symbols.json.gz", "Node.js")

    fun loadBundledBun(index: JsSymbolIndex) = loadBundledEnvironment(index, "/js/bun-symbols.json.gz", "Bun")

    fun loadBundledDeno(index: JsSymbolIndex) = loadBundledEnvironment(index, "/js/deno-symbols.json.gz", "Deno")

    /**
     * Every bundled symbol set, in the order the index expects them.
     *
     * `JsSymbolIndex.publish` swaps the whole index, so anything a rebuild leaves out disappears
     * from the editor. Both the initial load and the dependency watcher's re-index therefore go
     * through this one function — a re-index that skipped, say, the Node stage would silently take
     * `js/process` and the `fs` / `path` module exports away on the first `package.json` save.
     */
    fun loadAllBundled(index: JsSymbolIndex) {
        loadBundledBrowser(index)
        loadBundledGoog(index)
        loadBundledNode(index)
        loadBundledBun(index)
        loadBundledDeno(index)
    }

    fun loadNpmPackages(project: Project, index: JsSymbolIndex) {
        val settings = JsSupportSettings.getInstance().state
        val packages = project.service<NpmPackageResolver>().resolveAll()
        if (packages.isEmpty()) return

        val nodeExecutable = settings.nodeExecutablePath.ifBlank { null } ?: DtsParser.findNodeExecutable()
        if (nodeExecutable == null) {
            log.warn("Cursive JS Support: Node.js not found — skipping npm package type indexing")
            ApplicationManager.getApplication().invokeLater {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Cursive JS Support")
                    .createNotification(
                        "Cursive JS Support",
                        "Node.js was not found on PATH. npm package typings will not be loaded. Configure Node in Settings | Cursive JS Support.",
                        NotificationType.WARNING
                    )
                    .notify(project)
            }
            return
        }

        DtsParser(nodeExecutable).use { parser ->
            log.info("Cursive JS Support: loading types for ${packages.size} npm packages…")
            val t1 = System.currentTimeMillis()
            var loaded = 0
            var count = 0
            for (pkg in packages) {
                if (count >= settings.maxNpmPackages) {
                    log.warn("Cursive JS Support: max npm packages (${settings.maxNpmPackages}) reached — skipping rest")
                    break
                }
                count++
                try {
                    index.loadNpmPackage(pkg.packageName, parser.parse(pkg.files, pkg.entryFiles))
                    loaded++
                } catch (e: Exception) {
                    log.warn("Cursive JS Support: skipping ${pkg.packageName} — ${e.message}")
                }
            }
            log.info("Cursive JS Support: npm types ready — $loaded/${packages.size} packages (${System.currentTimeMillis() - t1}ms)")
        }
    }
}
