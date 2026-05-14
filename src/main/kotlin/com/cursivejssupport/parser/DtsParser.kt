package com.cursivejssupport.parser

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.util.EnvironmentUtil
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

// ─── Data model ──────────────────────────────────────────────────────────────

data class JsLocation(
    val filePath: String,
    val offset: Int
)

data class JsParam(
    val name: String = "arg",
    val type: String = "any",
    val optional: Boolean = false,
    val rest: Boolean = false
)

data class JsMember(
    val kind: String = "property",
    val params: List<JsParam> = emptyList(),
    val returns: String = "any",
    val type: String = "any",
    val optional: Boolean = false,
    val doc: String? = null,
    val location: JsLocation? = null
)

data class JsInterface(
    val location: JsLocation? = null,
    val extends: List<String> = emptyList(),
    val members: Map<String, List<JsMember>> = emptyMap()
)

data class JsVariableInfo(
    val type: String = "any",
    val doc: String? = null,
    val location: JsLocation? = null
)

data class ParsedSymbols(
    val interfaces: Map<String, JsInterface>    = emptyMap(),
    val variables:  Map<String, JsVariableInfo> = emptyMap(),
    val functions:  Map<String, List<JsMember>> = emptyMap()
)

// ─── Parser ───────────────────────────────────────────────────────────────────

class DtsParser(nodeExecutable: String) : AutoCloseable {

    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val tempDir: Path = Files.createTempDirectory("cursive-js-support-")
    private val process: Process
    private val writer:  BufferedWriter
    private val reader:  BufferedReader

    init {
        extractResource("/js/typescript.js", tempDir.resolve("typescript.js"))
        extractResource("/js/dts-extractor-runner.js", tempDir.resolve("runner.js"))

        process = ProcessBuilder(nodeExecutable, "--max-old-space-size=4096", tempDir.resolve("runner.js").toString())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        reader = process.inputStream.bufferedReader(Charsets.UTF_8)
    }

    fun parse(files: Map<String, String>): ParsedSymbols {
        val inputJson = mapper.writeValueAsString(files)
        writer.write(inputJson)
        writer.newLine()
        writer.flush()

        val line = reader.readLine() ?: run {
            val err = try {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                ""
            }
            throw IOException("Node.js dts-parser process exited unexpectedly${if (err.isNotBlank()) ": $err" else ""}")
        }
        val tree = mapper.readTree(line)
        if (tree.has("error")) throw RuntimeException("dts-parser: ${tree["error"].asText()}")
        return mapper.treeToValue(tree, ParsedSymbols::class.java)
    }

    override fun close() {
        runCatching { writer.close() }
        runCatching { process.waitFor(10, TimeUnit.SECONDS) }
        runCatching { process.destroyForcibly() }
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private fun extractResource(path: String, target: Path) {
        javaClass.getResourceAsStream(path)?.use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        } ?: error("Resource not found: $path")
    }

    companion object {
        fun findNodeExecutable(): String? {
            val envPath = EnvironmentUtil.getEnvironmentMap()["PATH"] ?: System.getenv("PATH") ?: ""
            val dirs = envPath.split(File.pathSeparatorChar).toMutableList()
            dirs.addAll(listOf("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin"))
            for (dir in dirs.distinct()) {
                val candidate = File(dir, "node")
                if (candidate.canExecute()) return candidate.absolutePath
            }
            return null
        }
    }
}