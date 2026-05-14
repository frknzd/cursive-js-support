package com.cursivejssupport.npm

import com.cursivejssupport.settings.JsSupportSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File

data class ResolvedNpmPackage(
    val packageName: String,
    val files: Map<String, String>  // absolute file path → .d.ts content
)

class NpmPackageResolver(
    project: Project,
    private val settings: JsSupportSettings.State = JsSupportSettings.getInstance().state,
) {

    private val log = logger<NpmPackageResolver>()
    private val mapper = jacksonObjectMapper()
    private val projectDir = File(project.basePath ?: "")
    private val nodeModules = File(projectDir, "node_modules")

    fun resolveAll(): List<ResolvedNpmPackage> {
        if (!nodeModules.exists()) return emptyList()

        return discoverAllDependencyPackageNames()
            .mapNotNull { resolve(it) }
            .also { log.info("Cursive JS Support: resolved types for ${it.size} npm packages") }
    }

    /** All package names to consider for completion and indexing. */
    fun discoverAllDependencyPackageNames(): Set<String> {
        val names = mutableSetOf<String>()

        File(projectDir, "package.json").takeIf { it.exists() }?.let { f ->
            names += parsePackageJson(f)
        }

        collectWorkspacePackageJsonFiles().forEach { f ->
            names += parsePackageJson(f)
        }

        File(projectDir, "shadow-cljs.edn").takeIf { it.exists() }?.let { f ->
            names += ShadowNpmDepsParser.collectNpmDepPackageNames(f)
        }

        if (settings.scanLockfileTransitive) {
            File(projectDir, "package-lock.json").takeIf { it.exists() }?.let { names += parsePackageLockJson(it) }
        }

        return names.filter { !it.startsWith("@types/") }.toSet()
    }

    private fun collectWorkspacePackageJsonFiles(): List<File> {
        val roots = mutableListOf<File>()
        val packagesDir = File(projectDir, "packages")
        if (packagesDir.isDirectory) {
            for (d in packagesDir.listFiles().orEmpty()) {
                val pj = File(d, "package.json")
                if (pj.isFile) roots += pj
            }
        }
        File(projectDir, "package.json").takeIf { it.isFile }?.let { root ->
            try {
                val rootNode = mapper.readTree(root)
                val workspaces = rootNode.path("workspaces")
                if (workspaces.isArray) {
                    for (w in workspaces) {
                        if (w.isTextual) {
                            val pattern = w.asText()
                            expandWorkspaceGlob(pattern).forEach { roots += it }
                        }
                    }
                }
            } catch (_: Exception) { /* ignore */ }
        }
        return roots.distinct()
    }

    private fun expandWorkspaceGlob(pattern: String): List<File> {
        if (!pattern.contains("*")) {
            val f = File(projectDir, pattern).resolve("package.json")
            return if (f.isFile) listOf(f) else emptyList()
        }
        val base = pattern.substringBefore("*").trimEnd('/')
        val dir = File(projectDir, base)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.mapNotNull { sub ->
            File(sub, "package.json").takeIf { it.isFile }
        }.orEmpty()
    }

    private fun packageDirectory(packageName: String): File {
        var dir = nodeModules
        for (part in packageName.split('/')) {
            if (part.isNotEmpty()) dir = File(dir, part)
        }
        return dir
    }

    /**
     * Entry `.d.ts` from the package's own `package.json` (`types` / `typings`), if the file exists under `node_modules`.
     */
    fun typingsEntryFile(packageName: String): File? {
        val pkgDir = packageDirectory(packageName)
        if (!pkgDir.isDirectory) return null
        val rel = findPackageOwnTypes(pkgDir) ?: return null
        return File(pkgDir, rel).takeIf { it.isFile }
    }

    private fun parsePackageJson(file: File): Set<String> = try {
        val root: JsonNode = mapper.readTree(file)
        setOf("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")
            .flatMap { key -> root.path(key).fields().asSequence().map { it.key }.toList() }
            .toSet()
    } catch (e: Exception) {
        log.warn("Could not parse package.json at ${file.path}", e)
        emptySet()
    }

    private fun parsePackageLockJson(file: File): Set<String> {
        return try {
            val root = mapper.readTree(file)
            val packages = root.path("packages")
            if (!packages.isObject) {
                emptySet()
            } else {
                val out = mutableSetOf<String>()
                for (name in packages.fieldNames().asSequence()) {
                    packageNameFromLockPath(name)?.let { out += it }
                }
                out
            }
        } catch (e: Exception) {
            log.warn("Could not parse package-lock.json", e)
            emptySet()
        }
    }

    private fun packageNameFromLockPath(path: String): String? {
        val trimmed = path.removePrefix("node_modules/").trim('/')
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("@")) {
            val secondSlash = trimmed.indexOf('/', 1)
            if (secondSlash > 0) trimmed.substring(0, secondSlash) else trimmed
        } else {
            trimmed.substringBefore('/').ifEmpty { null }
        }
    }

    private fun resolve(packageName: String): ResolvedNpmPackage? {
        val files = mutableMapOf<String, String>()

        val pkgDir = packageDirectory(packageName)
        if (pkgDir.exists()) {
            val entryDts = findPackageOwnTypes(pkgDir)
            if (entryDts != null) {
                files.putAll(collectDtsFiles(pkgDir, entryDts))
            }
        }

        if (files.isEmpty()) {
            val typesDir = File(nodeModules, "@types/${packageName.replace("/", "__")}")
            if (typesDir.exists()) {
                val index = File(typesDir, "index.d.ts")
                if (index.exists()) {
                    files.putAll(collectDtsFiles(typesDir, "index.d.ts"))
                }
            }
        }

        return if (files.isNotEmpty()) ResolvedNpmPackage(packageName, files) else null
    }

    private fun findPackageOwnTypes(pkgDir: File): String? = try {
        val pkgJson = mapper.readTree(File(pkgDir, "package.json"))
        pkgJson.path("types").takeIf { !it.isMissingNode }?.asText()
            ?: pkgJson.path("typings").takeIf { !it.isMissingNode }?.asText()
    } catch (_: Exception) {
        null
    }

    private fun collectDtsFiles(baseDir: File, entryName: String): Map<String, String> {
        val collected = mutableMapOf<String, String>()
        val queue = ArrayDeque<String>()
        queue.add(entryName)

        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            val file = File(baseDir, name)
            if (!file.exists()) continue

            val absPath = file.absolutePath
            if (absPath in collected) continue

            val content = file.readText()
            collected[absPath] = content

            Regex("""///\s*<reference\s+path="([^"]+)"""").findAll(content)
                .map { it.groupValues[1] }
                .filter { it.endsWith(".d.ts") && !it.startsWith("..") }
                .forEach { queue.add(it) }
        }

        return collected
    }
}
