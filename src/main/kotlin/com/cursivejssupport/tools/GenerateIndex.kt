package com.cursivejssupport.tools

import com.cursivejssupport.parser.DtsParser
import com.cursivejssupport.parser.withLogicalBundledLibPaths
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

fun findNodeForScript(): String {
    val envPath = System.getenv("PATH") ?: ""
    val dirs = envPath.split(File.pathSeparatorChar).toMutableList()
    dirs.addAll(listOf("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin"))

    for (dir in dirs.distinct()) {
        val candidate = File(dir, "node")
        if (candidate.canExecute()) return candidate.absolutePath
    }
    error("Node.js not found. Please ensure Node is installed.")
}

fun main() {
    val tsLibDir = File("src/main/resources/js/lib")

    if (!tsLibDir.exists() || !tsLibDir.isDirectory) {
        println("ERROR: Directory not found: ${tsLibDir.absolutePath}")
        return
    }

    val filesToParse = mutableMapOf<String, String>()

    val files = tsLibDir.listFiles() ?: emptyArray()
    for (file in files) {
        if (file.isFile && file.name.endsWith(".d.ts")) {
            // Absolute keys keep parser offsets aligned with the real file contents.
            filesToParse[file.absolutePath] = file.readText()
        }
    }

    if (filesToParse.isEmpty()) {
        println("ERROR: No TypeScript lib files found in ${tsLibDir.absolutePath}")
        return
    }

    val nodePath = findNodeForScript()
    println("Parsing ${filesToParse.size} files with new location logic...")

    val parser = DtsParser(nodePath)
    try {
        val parsedSymbols = parser.parse(filesToParse).withLogicalBundledLibPaths()

        val outputFile = File("src/main/resources/js/browser-symbols.json.gz")
        outputFile.parentFile.mkdirs()

        // Use standard try/finally for the GZIP stream as well just to be safe
        val fos = FileOutputStream(outputFile)
        val gz = GZIPOutputStream(fos)
        try {
            jacksonObjectMapper().writeValue(gz, parsedSymbols)
        } finally {
            gz.close()
            fos.close()
        }

        println("✅ Successfully generated new index at ${outputFile.absolutePath}")

        val loc = parsedSymbols.interfaces["Document"]?.members?.get("createRange")?.firstOrNull()?.location
        println("Verification -> Document.createRange location: $loc")
    } finally {
        parser.close()
    }
}