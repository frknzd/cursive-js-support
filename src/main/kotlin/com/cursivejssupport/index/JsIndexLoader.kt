package com.cursivejssupport.index

import com.cursivejssupport.npm.NpmPackageResolver
import com.cursivejssupport.parser.DtsParser
import com.cursivejssupport.parser.ParsedSymbols
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

    fun loadBundledBrowser(index: JsSymbolIndex) {
        val resourcePath = JsSupportSettings.getInstance().state.browserSymbolsResourcePath
        val stream = JsIndexLoader::class.java.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Pre-calculated index resource not found: $resourcePath")

        GZIPInputStream(stream).use { gz ->
            val symbols = mapper.readValue<ParsedSymbols>(gz)
            index.load(symbols)
        }
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
                    index.loadNpmPackage(pkg.packageName, parser.parse(pkg.files))
                    loaded++
                } catch (e: Exception) {
                    log.warn("Cursive JS Support: skipping ${pkg.packageName} — ${e.message}")
                }
            }
            log.info("Cursive JS Support: npm types ready — $loaded/${packages.size} packages (${System.currentTimeMillis() - t1}ms)")
        }
    }
}
