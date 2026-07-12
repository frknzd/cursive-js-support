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
        val match = LOCATION.find(line) ?: return null
        val rawPath = match.groupValues[1].removePrefix("file://")
        val lineNumber = match.groupValues[2].toIntOrNull()?.minus(1) ?: return null
        val column = match.groupValues[3].toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
        val direct = resolveFile(rawPath)
        val location = if (direct != null && direct.extension in setOf("cljs", "cljc")) {
            val remapped = project.service<SourceMapRegistry>().remapCopiedSource(direct.absolutePath)
            Triple(remapped ?: direct.path, lineNumber, column)
        } else {
            val mapped = project.service<SourceMapRegistry>().generatedToOriginal(rawPath, lineNumber, column) ?: return null
            Triple(mapped.source, mapped.line, mapped.column)
        }
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(location.first) ?: return null
        val start = entireLength - line.length + match.range.first
        val end = entireLength - line.length + match.range.last + 1
        return Filter.Result(start, end, OpenFileHyperlinkInfo(project, virtualFile, location.second, location.third))
    }

    private fun resolveFile(path: String): File? {
        val file = File(path)
        if (file.isFile) return file
        val root = project.basePath?.let(::File) ?: return null
        return File(root, path).takeIf { it.isFile }
    }

    companion object {
        private val LOCATION = Regex("(?:at\\s+.*?\\()?((?:[A-Za-z]:)?[^()\\s]+\\.(?:cljs|cljc|js|mjs|cjs)):(\\d+):(\\d+)\\)?")
    }
}
