package com.cursivejssupport.npm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/**
 * Resolves the path to a package's primary `.d.ts` from `package.json` and on-disk layout
 * (handles `types`, `typings`, string `exports`, conditional `exports`, and root `index.d.ts`).
 */
internal object NpmPackageTypings {

    private val mapper = jacksonObjectMapper()

    fun typingsEntryRelativePath(pkgDir: File, subpath: String? = null): String? {
        return try {
            val pkgJsonFile = File(pkgDir, "package.json")
            if (!pkgJsonFile.isFile) {
                null
            } else {
                val root = mapper.readTree(pkgJsonFile)

                if (!subpath.isNullOrBlank()) {
                    typesFromVersions(root.get("typesVersions"), subpath, pkgDir)
                        ?: typesFromExportsField(root.get("exports"), pkgDir, "./$subpath")
                        ?: directSubpathTypes(subpath, pkgDir)
                } else root.path("types").takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { normalizeTypesRelativePath(it) }
                    ?.takeIf { File(pkgDir, it).isFile }
                    ?: root.path("typings").takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { normalizeTypesRelativePath(it) }
                        ?.takeIf { File(pkgDir, it).isFile }
                    ?: typesFromExportsField(root.get("exports"), pkgDir, ".")
                    ?: "index.d.ts".takeIf { File(pkgDir, "index.d.ts").isFile }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeTypesRelativePath(p: String): String =
        p.trim().removePrefix("./")

    private fun typesFromExportsField(exports: JsonNode?, pkgDir: File, requestedKey: String): String? {
        if (exports == null || exports.isMissingNode || exports.isNull) return null
        return when {
            exports.isTextual && requestedKey == "." ->
                dtsBesideJavaScriptExport(exports.asText(), pkgDir)
            exports.isObject -> {
                typesFromSingleExportEntry(exports[requestedKey], pkgDir)
                    ?: exports.fields().asSequence().mapNotNull { (pattern, entry) ->
                        if (!pattern.contains('*')) return@mapNotNull null
                        val prefix = pattern.substringBefore('*')
                        val suffix = pattern.substringAfter('*')
                        if (!requestedKey.startsWith(prefix) || !requestedKey.endsWith(suffix)) return@mapNotNull null
                        val capture = requestedKey.removePrefix(prefix).removeSuffix(suffix)
                        typesFromSingleExportEntry(replaceStar(entry, capture), pkgDir)
                    }.firstOrNull()
            }
            else -> null
        }
    }

    private fun typesFromVersions(typesVersions: JsonNode?, subpath: String, pkgDir: File): String? {
        if (typesVersions == null || !typesVersions.isObject) return null
        for (versionEntry in typesVersions) {
            if (!versionEntry.isObject) continue
            for ((pattern, targets) in versionEntry.fields().asSequence().map { it.key to it.value }) {
                val capture = when {
                    pattern == subpath -> ""
                    pattern == "*" -> subpath
                    pattern.contains('*') && subpath.startsWith(pattern.substringBefore('*')) &&
                        subpath.endsWith(pattern.substringAfter('*')) ->
                        subpath.removePrefix(pattern.substringBefore('*')).removeSuffix(pattern.substringAfter('*'))
                    else -> continue
                }
                val candidates = if (targets.isArray) targets.toList() else listOf(targets)
                for (candidate in candidates) {
                    if (!candidate.isTextual) continue
                    val rel = normalizeTypesRelativePath(candidate.asText().replace("*", capture))
                    resolveDtsCandidate(pkgDir, rel)?.let { return it }
                }
            }
        }
        return null
    }

    private fun directSubpathTypes(subpath: String, pkgDir: File): String? =
        listOf("$subpath.d.ts", "$subpath/index.d.ts").firstOrNull { File(pkgDir, it).isFile }

    private fun resolveDtsCandidate(pkgDir: File, rel: String): String? = when {
        File(pkgDir, rel).isFile -> rel
        File(pkgDir, "$rel.d.ts").isFile -> "$rel.d.ts"
        File(pkgDir, "$rel/index.d.ts").isFile -> "$rel/index.d.ts"
        else -> null
    }

    private fun replaceStar(node: JsonNode, capture: String): JsonNode {
        if (node.isTextual) return mapper.nodeFactory.textNode(node.asText().replace("*", capture))
        if (!node.isObject) return node
        val copy = node.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        copy.fields().forEachRemaining { entry -> copy.set<JsonNode>(entry.key, replaceStar(entry.value, capture)) }
        return copy
    }

    private fun typesFromSingleExportEntry(entry: JsonNode?, pkgDir: File): String? {
        if (entry == null || entry.isMissingNode || entry.isNull) return null
        return when {
            entry.isTextual ->
                dtsBesideJavaScriptExport(entry.asText(), pkgDir)
            entry.isObject -> {
                entry.path("types").takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { normalizeTypesRelativePath(it) }
                    ?.takeIf { File(pkgDir, it).isFile }
                    ?: dtsBesideJavaScriptExport(
                        entry.path("import").takeIf { it.isTextual }?.asText()
                            ?: entry.path("require").takeIf { it.isTextual }?.asText()
                            ?: entry.path("default").takeIf { it.isTextual }?.asText(),
                        pkgDir,
                    )
                    ?: entry.fields().asSequence().mapNotNull { (_, nested) ->
                        typesFromSingleExportEntry(nested, pkgDir)
                    }.firstOrNull()
            }
            entry.isArray -> entry.asSequence().mapNotNull { typesFromSingleExportEntry(it, pkgDir) }.firstOrNull()
            else -> null
        }
    }

    private fun dtsBesideJavaScriptExport(jsOrDtsPath: String?, pkgDir: File): String? {
        if (jsOrDtsPath.isNullOrBlank()) return null
        val rel = normalizeTypesRelativePath(jsOrDtsPath)
        if (rel.endsWith(".d.ts", ignoreCase = true)) {
            return rel.takeIf { File(pkgDir, it).isFile }
        }
        if (rel.endsWith(".js", ignoreCase = true) ||
            rel.endsWith(".mjs", ignoreCase = true) ||
            rel.endsWith(".cjs", ignoreCase = true)
        ) {
            val base = rel.substringBeforeLast('.')
            val candidate = "$base.d.ts"
            return candidate.takeIf { File(pkgDir, it).isFile }
        }
        return null
    }
}
