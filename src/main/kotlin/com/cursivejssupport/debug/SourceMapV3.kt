package com.cursivejssupport.debug

import com.cursivejssupport.project.SourceMapPathMapping
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class SourceMapLocation(
    val source: String,
    val line: Int,
    val column: Int,
    val name: String? = null,
)

data class SourceMapSegment(
    val generatedLine: Int,
    val generatedColumn: Int,
    val original: SourceMapLocation?,
)

data class ParsedSourceMap(
    val generatedFile: String?,
    val sourceMapFile: String?,
    val segments: List<SourceMapSegment>,
    val sourcesContent: Map<String, String>,
) {
    fun generatedToOriginal(line: Int, column: Int): SourceMapLocation? = segments
        .asSequence()
        .filter { it.generatedLine == line && it.generatedColumn <= column && it.original != null }
        .maxByOrNull { it.generatedColumn }
        ?.original

    fun originalToGenerated(source: String, line: Int, column: Int = 0): SourceMapSegment? = segments
        .asSequence()
        .filter { segment ->
            val original = segment.original
            original != null && samePath(original.source, source) && original.line == line && original.column >= column
        }
        .minByOrNull { it.original!!.column }
        ?: segments.asSequence().filter { segment ->
            val original = segment.original
            original != null && samePath(original.source, source) && original.line >= line
        }.minWithOrNull(compareBy<SourceMapSegment> { it.original!!.line }.thenBy { it.original!!.column })
}

object SourceMapV3 {
    private val mapper = jacksonObjectMapper()

    fun parse(file: File, pathMappings: List<SourceMapPathMapping> = emptyList()): ParsedSourceMap =
        parse(file.readText(), file.absolutePath, pathMappings)

    fun parse(json: String, sourceMapFile: String? = null, pathMappings: List<SourceMapPathMapping> = emptyList()): ParsedSourceMap {
        val root = mapper.readTree(json)
        require(root.path("version").asInt() == 3) { "Only Source Map v3 is supported" }
        val mapDir = sourceMapFile?.let(::File)?.parentFile
        val segments = mutableListOf<SourceMapSegment>()
        val contents = linkedMapOf<String, String>()
        parseMap(root, mapDir, pathMappings, 0, 0, segments, contents)
        val generated = root.path("file").takeIf { it.isTextual }?.asText()?.let { resolvePath(mapDir, it, pathMappings) }
        return ParsedSourceMap(generated, sourceMapFile, segments.sortedWith(compareBy({ it.generatedLine }, { it.generatedColumn })), contents)
    }

    fun parseInlineDataUrl(url: String, pathMappings: List<SourceMapPathMapping> = emptyList()): ParsedSourceMap {
        require(url.startsWith("data:")) { "Not a data URL" }
        val payload = url.substringAfter(',')
        val json = if (url.substringBefore(',').endsWith(";base64")) {
            String(java.util.Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
        } else URLDecoder.decode(payload, StandardCharsets.UTF_8)
        return parse(json, pathMappings = pathMappings)
    }

    private fun parseMap(
        root: JsonNode,
        mapDir: File?,
        pathMappings: List<SourceMapPathMapping>,
        lineOffset: Int,
        columnOffset: Int,
        output: MutableList<SourceMapSegment>,
        contents: MutableMap<String, String>,
    ) {
        val sections = root.path("sections")
        if (sections.isArray) {
            for (section in sections) {
                val offset = section.path("offset")
                val childLine = lineOffset + offset.path("line").asInt()
                val childColumn = if (offset.path("line").asInt() == 0) columnOffset + offset.path("column").asInt() else offset.path("column").asInt()
                val child = section.path("map")
                if (child.isObject) {
                    parseMap(child, mapDir, pathMappings, childLine, childColumn, output, contents)
                } else {
                    val url = section.path("url").takeIf { it.isTextual }?.asText()
                    val external = url?.let { mapDir?.resolve(URLDecoder.decode(it, StandardCharsets.UTF_8)) }
                    if (external?.isFile == true) {
                        parseMap(mapper.readTree(external), external.parentFile, pathMappings, childLine, childColumn, output, contents)
                    }
                }
            }
            return
        }

        val sourceRoot = root.path("sourceRoot").takeIf { it.isTextual }?.asText().orEmpty()
        val sources = root.path("sources").map { raw ->
            val joined = if (sourceRoot.isBlank()) raw.asText() else sourceRoot.trimEnd('/') + "/" + raw.asText().trimStart('/')
            resolvePath(mapDir, joined, pathMappings)
        }
        val names = root.path("names").map { it.asText() }
        root.path("sourcesContent").takeIf { it.isArray }?.forEachIndexed { i, node ->
            if (!node.isNull && i < sources.size) contents[sources[i]] = node.asText()
        }

        var sourceIndex = 0
        var originalLine = 0
        var originalColumn = 0
        var nameIndex = 0
        val lines = root.path("mappings").asText().split(';')
        for ((lineIndex, encodedLine) in lines.withIndex()) {
            var generatedColumn = 0
            for (encoded in encodedLine.split(',').filter { it.isNotEmpty() }) {
                val values = Vlq.decode(encoded)
                if (values.isEmpty()) continue
                generatedColumn += values[0]
                var original: SourceMapLocation? = null
                if (values.size >= 4) {
                    sourceIndex += values[1]
                    originalLine += values[2]
                    originalColumn += values[3]
                    if (values.size >= 5) nameIndex += values[4]
                    val source = sources.getOrNull(sourceIndex)
                    if (source != null) original = SourceMapLocation(source, originalLine, originalColumn, names.getOrNull(nameIndex))
                }
                val actualColumn = generatedColumn + if (lineIndex == 0) columnOffset else 0
                output += SourceMapSegment(lineOffset + lineIndex, actualColumn, original)
            }
        }
    }

    private fun resolvePath(mapDir: File?, raw: String, mappings: List<SourceMapPathMapping>): String {
        val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8).removePrefix("webpack:///").removePrefix("file://")
        val resolved = if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else
            File(decoded).let { if (it.isAbsolute || mapDir == null) it else File(mapDir, decoded) }.normalize().path
        for (mapping in mappings.sortedByDescending { it.remotePrefix.length }) {
            val matched = when {
                decoded.startsWith(mapping.remotePrefix) -> decoded.removePrefix(mapping.remotePrefix)
                resolved.startsWith(mapping.remotePrefix) -> resolved.removePrefix(mapping.remotePrefix)
                else -> null
            }
            if (matched != null) {
                return File(mapping.localPath, matched.trimStart('/')).normalize().absolutePath
            }
        }
        return resolved
    }
}

private object Vlq {
    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(segment: String): IntArray {
        val out = mutableListOf<Int>()
        var value = 0
        var shift = 0
        for (char in segment) {
            val digit = BASE64.indexOf(char)
            require(digit >= 0) { "Invalid base64 VLQ digit '$char'" }
            val continuation = digit and 32
            value += (digit and 31) shl shift
            if (continuation != 0) {
                shift += 5
            } else {
                val negative = value and 1 == 1
                val decoded = value shr 1
                out += if (negative) -decoded else decoded
                value = 0
                shift = 0
            }
        }
        require(shift == 0) { "Truncated VLQ segment" }
        return out.toIntArray()
    }
}

private fun samePath(a: String, b: String): Boolean {
    fun normalize(value: String): String = runCatching { URI(value).path ?: value }.getOrDefault(value).replace('\\', '/').trimStart('/')
    val left = normalize(a)
    val right = normalize(b)
    return left == right || left.endsWith("/$right") || right.endsWith("/$left")
}
