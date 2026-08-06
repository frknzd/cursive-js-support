package com.cursivejssupport.tools

import com.cursivejssupport.parser.DtsParser
import com.cursivejssupport.parser.ParsedSymbols
import com.cursivejssupport.parser.withLogicalBundledLibPaths
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Shared driver for the bundled environment index generators (Node / Bun / Deno).
 *
 * Reads every `*.d.ts` under [sourceDir] (recursively), runs the extractor with the given
 * [environment] wire key, rewrites locations to logical bundled paths, and writes a gzipped
 * [ParsedSymbols] JSON document to [outputFile].
 *
 * Unlike the browser index, these type sets are dominated by ambient external modules
 * (`declare module "fs" { … }`); the extractor scopes them into [ParsedSymbols.modules] so
 * the loader can register each one as a package.
 */
fun generateBundledEnvironmentIndex(
    sourceDir: File,
    outputFile: File,
    environment: String,
    logicalPrefix: String,
) {
    if (!sourceDir.exists() || !sourceDir.isDirectory) {
        println("ERROR: Directory not found: ${sourceDir.absolutePath}")
        return
    }

    val filesToParse = mutableMapOf<String, String>()
    sourceDir.walkTopDown().forEach { file ->
        if (file.isFile && file.name.endsWith(".d.ts")) {
            filesToParse[file.absolutePath] = file.readText()
        }
    }
    if (filesToParse.isEmpty()) {
        println("ERROR: No TypeScript .d.ts files found in ${sourceDir.absolutePath}")
        return
    }

    val nodePath = DtsParser.findNodeExecutable()
        ?: error("Node.js not found on PATH or in /usr/local/bin, /opt/homebrew/bin, /usr/bin, /bin.")
    println("Parsing ${filesToParse.size} files for $environment…")

    val parser = DtsParser(nodePath)
    try {
        val parsedSymbols = parser.parse(filesToParse, environment = environment)
            .withLogicalBundledLibPaths(logicalPrefix)

        outputFile.parentFile.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            GZIPOutputStream(fos).use { gz ->
                jacksonObjectMapper().writeValue(gz, parsedSymbols)
            }
        }
        println("Successfully generated $environment index at ${outputFile.absolutePath}")
        println("  modules: ${parsedSymbols.modules.size}, globals: ${parsedSymbols.variables.size}, interfaces: ${parsedSymbols.interfaces.size}")
    } finally {
        parser.close()
    }
}

fun main() = generateBundledEnvironmentIndex(
    sourceDir = File("src/main/resources/js/node-types"),
    outputFile = File("src/main/resources/js/node-symbols.json.gz"),
    environment = "node",
    logicalPrefix = "js/node-types/",
)
