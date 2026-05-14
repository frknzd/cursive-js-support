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

    fun typingsEntryRelativePath(pkgDir: File): String? {
        return try {
            val pkgJsonFile = File(pkgDir, "package.json")
            if (!pkgJsonFile.isFile) {
                null
            } else {
                val root = mapper.readTree(pkgJsonFile)

                root.path("types").takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { normalizeTypesRelativePath(it) }
                    ?.takeIf { File(pkgDir, it).isFile }
                    ?: root.path("typings").takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { normalizeTypesRelativePath(it) }
                        ?.takeIf { File(pkgDir, it).isFile }
                    ?: typesFromExportsField(root.get("exports"), pkgDir)
                    ?: "index.d.ts".takeIf { File(pkgDir, "index.d.ts").isFile }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeTypesRelativePath(p: String): String =
        p.trim().removePrefix("./")

    private fun typesFromExportsField(exports: JsonNode?, pkgDir: File): String? {
        if (exports == null || exports.isMissingNode || exports.isNull) return null
        return when {
            exports.isTextual ->
                dtsBesideJavaScriptExport(exports.asText(), pkgDir)
            exports.isObject -> {
                val dot = exports["."]
                typesFromSingleExportEntry(dot, pkgDir)
                    ?: exports.fields().asSequence()
                        .filter { (k, _) -> k != "." && !k.contains("package.json", ignoreCase = true) }
                        .mapNotNull { (_, v) -> typesFromSingleExportEntry(v, pkgDir) }
                        .firstOrNull()
            }
            else -> null
        }
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
            }
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
