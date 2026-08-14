package com.cursivejssupport.debug

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

class CljsConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(CljsConsoleFilter(project))
}

private class CljsConsoleFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val match = CljsConsoleLocationParser.find(line) ?: return null
        val rawPath = match.path.removePrefix("file://")
        val direct = resolveFile(rawPath)
        val location = if (direct != null && direct.extension in setOf("cljs", "cljc")) {
            val remapped = project.service<SourceMapRegistry>().remapCopiedSource(direct.absolutePath)
            Triple(remapped ?: direct.path, match.line, match.column)
        } else {
            val mapped = project.service<SourceMapRegistry>().generatedToOriginal(rawPath, match.line, match.column) ?: return null
            Triple(mapped.source, mapped.line, mapped.column)
        }
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(location.first) ?: return null
        val start = entireLength - line.length + match.startOffset
        val end = entireLength - line.length + match.endOffset
        return Filter.Result(start, end, OpenFileHyperlinkInfo(project, virtualFile, location.second, location.third))
    }

    private fun resolveFile(path: String): File? {
        val file = File(path)
        if (file.isFile) return file
        val root = project.basePath?.let(::File) ?: return null
        return File(root, path).takeIf { it.isFile }
    }
}

internal data class CljsConsoleLocation(
    val path: String,
    val line: Int,
    val column: Int,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * Finds source locations without a whole-line path regex. REPL launch commands can contain tens of
 * thousands of non-whitespace characters, making an unanchored greedy path match quadratic.
 */
internal object CljsConsoleLocationParser {
    private val extensions = arrayOf(".cljs", ".cljc", ".mjs", ".cjs", ".js")

    fun find(line: String): CljsConsoleLocation? {
        var dot = line.indexOf('.')
        while (dot >= 0) {
            val extension = extensions.firstOrNull { line.regionMatches(dot, it, 0, it.length) }
            if (extension != null) {
                parseAt(line, dot, extension)?.let { return it }
            }
            dot = line.indexOf('.', dot + 1)
        }
        return null
    }

    private fun parseAt(line: String, extensionStart: Int, extension: String): CljsConsoleLocation? {
        val pathEnd = extensionStart + extension.length
        if (pathEnd >= line.length || line[pathEnd] != ':') return null

        val lineStart = pathEnd + 1
        val lineEnd = line.scanAsciiDigits(lineStart)
        if (lineEnd == lineStart || lineEnd >= line.length || line[lineEnd] != ':') return null

        val columnStart = lineEnd + 1
        val columnEnd = line.scanAsciiDigits(columnStart)
        if (columnEnd == columnStart) return null

        val lineNumber = line.substring(lineStart, lineEnd).toIntOrNull()?.minus(1) ?: return null
        val column = line.substring(columnStart, columnEnd).toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: return null

        var pathStart = extensionStart
        while (pathStart > 0 && !line[pathStart - 1].isLocationBoundary()) {
            pathStart--
        }
        if (pathStart == extensionStart) return null

        return CljsConsoleLocation(
            path = line.substring(pathStart, pathEnd),
            line = lineNumber,
            column = column,
            startOffset = pathStart,
            endOffset = columnEnd,
        )
    }

    private fun String.scanAsciiDigits(start: Int): Int {
        var index = start
        while (index < length && this[index] in '0'..'9') index++
        return index
    }

    private fun Char.isLocationBoundary(): Boolean = isWhitespace() || this == '(' || this == ')'
}
