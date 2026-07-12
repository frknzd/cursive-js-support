package com.cursivejssupport.npm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/** Resolves every declaration or runtime entry applicable to a package request. */
internal object NpmPackageTypings {
    private val mapper = jacksonObjectMapper()
    private val declarationSuffixes = listOf(".d.ts", ".d.cts", ".d.mts")
    private val runtimeSuffixes = listOf(".js", ".mjs", ".cjs", ".jsx")

    fun typingsEntryRelativePath(pkgDir: File, subpath: String? = null): String? =
        typingsEntryRelativePaths(pkgDir, subpath).firstOrNull()

    fun typingsEntryRelativePaths(pkgDir: File, subpath: String? = null): List<String> = runCatching {
        packageJson(pkgDir) { root -> buildSet {
            if (subpath.isNullOrBlank()) {
                addTextPath(root, "types", pkgDir)
                addTextPath(root, "typings", pkgDir)
            } else {
                addAll(typesVersionPaths(root.path("typesVersions"), subpath, pkgDir))
            }
            selectedExportEntries(root.get("exports"), requestedKey(subpath)).forEach { entry ->
                collectDeclarationPaths(entry, pkgDir, this)
            }
            if (isEmpty()) addAll(directDeclarationPaths(pkgDir, subpath))
        }.toList() }
    }.getOrDefault(emptyList())

    fun runtimeEntryRelativePaths(pkgDir: File, subpath: String? = null): List<String> = runCatching {
        packageJson(pkgDir) { root -> buildSet {
            selectedExportEntries(root.get("exports"), requestedKey(subpath)).forEach { entry ->
                collectRuntimePaths(entry, pkgDir, this)
            }
            if (subpath.isNullOrBlank()) {
                listOf("module", "main", "browser").forEach { addTextRuntimePath(root, it, pkgDir) }
            } else {
                runtimeSuffixes.forEach { suffix ->
                    normalize(subpath + suffix).takeIf { File(pkgDir, it).isFile }?.let(::add)
                }
                normalize("$subpath/index.js").takeIf { File(pkgDir, it).isFile }?.let(::add)
            }
        }.toList() }
    }.getOrDefault(emptyList())

    private fun <T> packageJson(pkgDir: File, block: (JsonNode) -> T): T {
        val file = File(pkgDir, "package.json")
        require(file.isFile) { "Missing package.json in ${pkgDir.path}" }
        return block(mapper.readTree(file))
    }

    private fun requestedKey(subpath: String?) = subpath?.takeIf(String::isNotBlank)?.let { "./$it" } ?: "."

    private fun MutableSet<String>.addTextPath(root: JsonNode, field: String, pkgDir: File) {
        root.path(field).takeIf(JsonNode::isTextual)?.asText()?.let(::normalize)
            ?.takeIf { File(pkgDir, it).isFile && isDeclaration(it) }
            ?.let(::add)
    }

    private fun MutableSet<String>.addTextRuntimePath(root: JsonNode, field: String, pkgDir: File) {
        root.path(field).takeIf(JsonNode::isTextual)?.asText()?.let(::normalize)
            ?.takeIf { File(pkgDir, it).isFile && isRuntime(it) }
            ?.let(::add)
    }

    private fun selectedExportEntries(exports: JsonNode?, key: String): List<JsonNode> {
        if (exports == null || exports.isNull || exports.isMissingNode) return emptyList()
        if (!exports.isObject) return if (key == ".") listOf(exports) else emptyList()
        val fields = exports.fields().asSequence().toList()
        val isSubpathMap = fields.any { it.key.startsWith(".") }
        if (!isSubpathMap) return if (key == ".") listOf(exports) else emptyList()
        exports.get(key)?.let { return listOf(it) }
        return fields.mapNotNull { (pattern, entry) -> matchPattern(pattern, key)?.let { replaceStar(entry, it) } }
    }

    private fun matchPattern(pattern: String, requested: String): String? {
        if ('*' !in pattern) return null
        val prefix = pattern.substringBefore('*')
        val suffix = pattern.substringAfter('*')
        return requested.takeIf { it.startsWith(prefix) && it.endsWith(suffix) }
            ?.removePrefix(prefix)?.removeSuffix(suffix)
    }

    private fun collectDeclarationPaths(node: JsonNode, pkgDir: File, output: MutableSet<String>) {
        when {
            node.isTextual -> declarationFor(node.asText(), pkgDir)?.let(output::add)
            node.isArray -> node.forEach { collectDeclarationPaths(it, pkgDir, output) }
            node.isObject -> node.fields().forEachRemaining { collectDeclarationPaths(it.value, pkgDir, output) }
        }
    }

    private fun collectRuntimePaths(node: JsonNode, pkgDir: File, output: MutableSet<String>) {
        when {
            node.isTextual -> normalize(node.asText()).takeIf { isRuntime(it) && File(pkgDir, it).isFile }?.let(output::add)
            node.isArray -> node.forEach { collectRuntimePaths(it, pkgDir, output) }
            node.isObject -> node.fields().forEachRemaining { collectRuntimePaths(it.value, pkgDir, output) }
        }
    }

    private fun declarationFor(path: String, pkgDir: File): String? {
        val relative = normalize(path)
        if (isDeclaration(relative)) return relative.takeIf { File(pkgDir, it).isFile }
        if (!isRuntime(relative)) return null
        val base = relative.substringBeforeLast('.')
        return declarationSuffixes.asSequence().map { base + it }.firstOrNull { File(pkgDir, it).isFile }
    }

    private fun directDeclarationPaths(pkgDir: File, subpath: String?): List<String> {
        val base = subpath?.takeIf(String::isNotBlank) ?: "index"
        return buildList {
            declarationSuffixes.forEach { suffix ->
                normalize(base + suffix).takeIf { File(pkgDir, it).isFile }?.let(::add)
            }
            declarationSuffixes.forEach { suffix ->
                normalize("$base/index$suffix").takeIf { File(pkgDir, it).isFile }?.let(::add)
            }
        }
    }

    private fun typesVersionPaths(node: JsonNode, subpath: String, pkgDir: File): List<String> {
        if (!node.isObject) return emptyList()
        val output = mutableListOf<String>()
        for (versionEntry in node) {
            if (!versionEntry.isObject) continue
            for ((pattern, targets) in versionEntry.fields().asSequence()) {
                val capture = when {
                    pattern == subpath -> ""
                    pattern == "*" -> subpath
                    else -> matchPattern(pattern, subpath) ?: continue
                }
                val candidates = if (targets.isArray) targets.toList() else listOf(targets)
                candidates.filter(JsonNode::isTextual).forEach { target ->
                    val relative = normalize(target.asText().replace("*", capture))
                    resolveDeclarationCandidate(pkgDir, relative)?.let(output::add)
                }
            }
        }
        return output
    }

    private fun resolveDeclarationCandidate(pkgDir: File, path: String): String? {
        val candidates = mutableListOf(path)
        declarationSuffixes.mapTo(candidates) { path + it }
        declarationSuffixes.mapTo(candidates) { "$path/index$it" }
        return candidates.firstOrNull { File(pkgDir, it).isFile }
    }

    private fun replaceStar(node: JsonNode, capture: String): JsonNode {
        if (node.isTextual) return mapper.nodeFactory.textNode(node.asText().replace("*", capture))
        if (!node.isObject) return node
        return node.deepCopy<ObjectNode>().also { copy ->
            copy.fields().forEachRemaining { (key, value) -> copy.set<JsonNode>(key, replaceStar(value, capture)) }
        }
    }

    private fun normalize(path: String) = path.trim().removePrefix("./")
    private fun isDeclaration(path: String) = declarationSuffixes.any(path::endsWith)
    private fun isRuntime(path: String) = runtimeSuffixes.any(path::endsWith)
}
