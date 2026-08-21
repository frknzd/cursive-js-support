package com.cursivejssupport.debug

import com.cursivejssupport.project.CljsProjectModel
import com.cursivejssupport.project.SourceMapPathMapping
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SourceMapRegistry(private val project: Project) {
    private data class Cached(val modified: Long, val map: ParsedSourceMap)
    private val cache = ConcurrentHashMap<String, Cached>()
    @Volatile private var configuredRoots: List<String> = emptyList()
    @Volatile private var configuredMappings: List<SourceMapPathMapping> = emptyList()

    fun configure(roots: List<String>, mappings: List<SourceMapPathMapping>) {
        configuredRoots = roots
        configuredMappings = mappings
        cache.clear()
    }

    fun maps(): List<ParsedSourceMap> {
        val roots = (CljsProjectModel.getInstance(project).profiles.flatMap { it.sourceMapRoots } + configuredRoots).distinct()
        val allFiles = roots.asSequence().map(::File).filter { it.isDirectory }
            .flatMap { it.walkTopDown().maxDepth(8).filter(File::isFile) }
            .filter { it.extension in SOURCE_MAP_EXTENSIONS }
            .take(5_000).toList()
        val external = allFiles.filter { it.extension == "map" }.mapNotNull { file ->
            val key = file.absolutePath
            val existing = cache[key]
            if (existing != null && existing.modified == file.lastModified()) existing.map
            else runCatching { SourceMapV3.parse(file, configuredMappings) }.getOrNull()?.also { cache[key] = Cached(file.lastModified(), it) }
        }
        val inline = allFiles.filter { it.extension in setOf("js", "mjs", "cjs") && it.length() <= 10_000_000 }.mapNotNull { file ->
            val key = "inline:${file.absolutePath}"
            val existing = cache[key]
            if (existing != null && existing.modified == file.lastModified()) return@mapNotNull existing.map
            val dataUrl = INLINE_MAP.find(file.readText())?.groupValues?.get(1) ?: return@mapNotNull null
            runCatching { SourceMapV3.parseInlineDataUrl(dataUrl, configuredMappings).copy(generatedFile = file.absolutePath) }.getOrNull()
                ?.also { cache[key] = Cached(file.lastModified(), it) }
        }
        return external + inline
    }

    fun generatedToOriginal(generatedFile: String, line: Int, column: Int): SourceMapLocation? = maps()
        .firstOrNull { map -> map.generatedFile?.let { sameFile(it, generatedFile) } == true || map.sourceMapFile?.removeSuffix(".map")?.let { sameFile(it, generatedFile) } == true }
        ?.generatedToOriginal(line, column)

    fun originalToGenerated(source: String, line: Int, column: Int = 0): Pair<ParsedSourceMap, SourceMapSegment>? = maps()
        .asSequence().mapNotNull { map -> map.originalToGenerated(source, line, column)?.let { map to it } }.firstOrNull()

    fun remapCopiedSource(path: String): String? {
        for (mapping in configuredMappings.sortedByDescending { it.remotePrefix.length }) {
            if (!path.startsWith(mapping.remotePrefix)) continue
            val candidate = File(mapping.localPath, path.removePrefix(mapping.remotePrefix).trimStart('/'))
            if (candidate.isFile) return candidate.normalize().absolutePath
        }
        return null
    }

    private fun sameFile(a: String, b: String): Boolean = File(a).normalize().path.replace('\\', '/') == File(b).normalize().path.replace('\\', '/') ||
        a.replace('\\', '/').endsWith("/${File(b).name}")

    companion object {
        private val SOURCE_MAP_EXTENSIONS = setOf("map", "js", "mjs", "cjs")
        private val INLINE_MAP = Regex("sourceMappingURL=(data:[^\\s]+)")
    }
}
