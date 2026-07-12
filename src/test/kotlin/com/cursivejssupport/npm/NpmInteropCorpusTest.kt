package com.cursivejssupport.npm

import com.cursivejssupport.index.JsSymbolIndex
import com.cursivejssupport.parser.DtsParser
import com.cursivejssupport.settings.JsSupportSettings
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NpmInteropCorpusTest {
    private val corpus = File("test-fixtures/npm-interop-corpus")
    private val anchors = mapOf(
        "lodash" to listOf("default"),
        "@reduxjs/toolkit" to listOf("configureStore", "createSlice"),
        "ajv" to listOf("default", "Ajv"),
        "axios" to listOf("default", "AxiosError"),
        "chalk" to listOf("default", "chalkStderr"),
        "commander" to listOf("Command", "program"),
        "d3" to listOf("select", "scaleLinear"),
        "date-fns" to listOf("format", "addDays"),
        "diff" to listOf("diffArrays", "structuredPatch"),
        "express" to listOf("default"),
        "node-fetch" to listOf("default", "Headers"),
        "react" to listOf("default"),
        "react-router-dom" to listOf("BrowserRouter", "useNavigate"),
        "rxjs" to listOf("Observable", "map"),
        "three" to listOf("Scene", "WebGLRenderer"),
        "typescript" to listOf("version", "versionMajorMinor"),
        "uuid" to listOf("v4", "validate"),
        "vite" to listOf("defineConfig", "createServer"),
        "ws" to listOf("default", "WebSocketServer"),
        "zod" to listOf("z", "object"),
        "@reduxjs/toolkit/query" to listOf("createApi", "fetchBaseQuery"),
        "ajv/dist/2020" to listOf("default"),
        "date-fns/addDays" to listOf("addDays"),
        "lodash/fp" to listOf("default"),
        "react/jsx-runtime" to listOf("jsx", "jsxs"),
        "rxjs/operators" to listOf("map", "filter"),
        "vite/module-runner" to listOf("ModuleRunner"),
        "zod/v4" to listOf("z", "object"),
    )

    @Test
    fun `fallback matches TypeScript value exports and preserves editor semantic data`() {
        val node = DtsParser.findNodeExecutable()
        assumeNotNull(node)
        val installed = File(corpus, "node_modules").isDirectory
        if (System.getProperty("npmInteropAudit.required") == "true" && !installed) {
            error("run npm ci in ${corpus.path}")
        }
        assumeTrue("run npm ci in ${corpus.path}", installed)

        val reference = referenceReport(node!!)
        val resolver = NpmPackageResolver(corpus, JsSupportSettings.State())
        val failures = mutableListOf<String>()

        DtsParser(node).use { parser ->
            anchors.forEach { (packageName, required) ->
                val resolved = resolver.resolvePackage(packageName)
                if (resolved == null) {
                    failures += "$packageName: no declaration entry"
                    return@forEach
                }
                val symbols = parser.parse(resolved.files)
                val index = JsSymbolIndex().apply { loadNpmPackage(packageName, symbols) }
                val actual = index.npmExportNames(packageName).toSet()
                val referenceExports = reference.path(packageName).path("exports")
                val expected = referenceExports.map { it.path("name").asText() }.toSet()
                val missing = expected - actual
                if (missing.isNotEmpty()) failures += "$packageName: missing ${missing.size}/${expected.size} value exports ${missing.sorted().take(12)}"
                val unexpected = actual - expected
                if (unexpected.isNotEmpty()) {
                    failures += "$packageName: exposes non-runtime exports ${unexpected.sorted().take(12)} " +
                        "from ${resolved.entryFiles} (public=${symbols.moduleExports})"
                }
                val missingAnchors = required.filterNot(actual::contains)
                if (missingAnchors.isNotEmpty()) failures += "$packageName: missing representative exports $missingAnchors"

                referenceExports.filter { it.path("name").asText() in actual }.forEach { expectedExport ->
                    val exportName = expectedExport.path("name").asText()
                    val location = symbols.variables[exportName]?.location
                        ?: symbols.functions[exportName]?.firstOrNull()?.location
                    if (expectedExport.path("declaration").isObject && location == null) {
                        failures += "$packageName/$exportName: no declaration target"
                    }
                    if (location != null) {
                        val target = File(location.filePath)
                        if (!target.isFile || location.offset.toLong() !in 0 until target.length().coerceAtLeast(1)) {
                            failures += "$packageName/$exportName: invalid declaration target ${location.filePath}:${location.offset}"
                        }
                    }
                    val type = index.resolveNpmExportType(packageName, exportName)
                    if (type.isNullOrBlank()) failures += "$packageName/$exportName: no hover type"
                    if (exportName in required && type in setOf("any", "unknown")) {
                        failures += "$packageName/$exportName: non-meaningful hover type $type"
                    }

                    val expectedCalls = expectedExport.path("calls").asInt()
                    val actualCalls = index.resolveNpmExportMembers(packageName, exportName).orEmpty()
                    if (actualCalls.size < expectedCalls) {
                        failures += "$packageName/$exportName: signatures ${actualCalls.size}/$expectedCalls"
                    }
                    if (expectedExport.path("documentation").asText().isNotBlank() &&
                        index.resolveNpmExportDoc(packageName, exportName).isNullOrBlank()
                    ) {
                        failures += "$packageName/$exportName: missing hover documentation"
                    }

                    if (type != null) {
                        val actualMembers = index.resolveMembers(type).keys
                        val expectedMembers = expectedExport.path("members").map { it.asText() }.toSet()
                        val missingMembers = expectedMembers - actualMembers
                        if (missingMembers.isNotEmpty()) {
                            failures += "$packageName/$exportName: missing ${missingMembers.size}/${expectedMembers.size} completion members ${missingMembers.sorted().take(8)}"
                        }
                        if (expectedExport.path("constructs").asInt() > 0 && "new" !in actualMembers) {
                            failures += "$packageName/$exportName: missing constructor signature"
                        }
                    }
                }
            }
        }

        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun referenceReport(node: String) = ProcessBuilder(node, "reference-report.cjs")
        .directory(corpus)
        .redirectErrorStream(true)
        .start()
        .let { process ->
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { output }
            jacksonObjectMapper().readTree(output)
        }
}
