package com.cursivejssupport.npm

import com.intellij.openapi.diagnostic.logger
import us.bpsm.edn.Keyword
import us.bpsm.edn.parser.Parsers
import java.io.File
import java.io.StringReader
import java.nio.file.Files

/**
 * EDN-aware extraction of `:npm-deps` package names from shadow-cljs.edn (and similarly shaped files).
 */
object ShadowNpmDepsParser {

    private val log = logger<ShadowNpmDepsParser>()

    fun collectNpmDepPackageNames(file: File): Set<String> {
        if (!file.exists()) return emptySet()
        return try {
            val text = Files.readString(file.toPath())
            collectFromEdnText(text)
        } catch (e: Exception) {
            log.warn("Failed to read ${file.path}", e)
            emptySet()
        }
    }

    fun collectFromEdnText(text: String): Set<String> {
        val out = mutableSetOf<String>()
        try {
            val pbr = Parsers.newParseable(StringReader(text))
            val parser = Parsers.newParser(Parsers.defaultConfiguration())
            collectFromValue(parser.nextValue(pbr), out)
        } catch (_: Exception) {
            // Malformed EDN — surface nothing rather than crashing the contributor.
        }
        return out
    }

    private fun collectFromValue(value: Any?, out: MutableSet<String>) {
        when (value) {
            is List<*> -> for (e in value) collectFromValue(e, out)
            is Map<*, *> -> {
                for ((k, v) in value) {
                    val isNpmDeps = when (k) {
                        is Keyword -> k.name == "npm-deps"
                        is String -> k == "npm-deps" || k == ":npm-deps"
                        else -> false
                    }
                    if (isNpmDeps && v is Map<*, *>) {
                        for (pkgKey in v.keys) {
                            when (pkgKey) {
                                is String -> if (pkgKey.isNotBlank() && !pkgKey.startsWith("@types/")) out += pkgKey
                                is Keyword -> {
                                    val n = pkgKey.name
                                    if (n.isNotBlank() && !n.startsWith("@types/")) out += n
                                }
                            }
                        }
                    }
                    collectFromValue(v, out)
                }
            }
            is Set<*> -> for (e in value) collectFromValue(e, out)
            else -> { /* leaf */ }
        }
    }
}
