package com.cursivejssupport.npm

import com.cursivejssupport.settings.JsSupportSettings
import com.cursivejssupport.util.InteropDebugLog
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
    private val projectDir: File,
    private val settings: JsSupportSettings.State,
) {

    private val log = logger<NpmPackageResolver>()
    private val mapper = jacksonObjectMapper()
    private val nodeModules = File(projectDir, "node_modules")

    constructor(
        project: Project,
        settings: JsSupportSettings.State = JsSupportSettings.getInstance().state,
    ) : this(File(project.basePath ?: ""), settings)

    fun resolveAll(): List<ResolvedNpmPackage> {
        if (!nodeModules.exists()) return emptyList()

        return discoverAllDependencyPackageNames(anchorFilePath = null)
            .mapNotNull { resolve(it) }
            .also { log.info("Cursive JS Support: resolved types for ${it.size} npm packages") }
    }

    /**
     * All package names for completion / indexing: merges [package.json] deps, workspace package.json files,
     * [:npm-deps] from [shadow-cljs.edn], and optionally [package-lock.json], from the IDE project root and from
     * every directory up the chain from [anchorFilePath] that contains any of those files (nested CLJS apps).
     */
    fun discoverAllDependencyPackageNames(anchorFilePath: String? = null): Set<String> {
        val names = mutableSetOf<String>()
        val roots = candidateNpmDiscoveryRoots(anchorFilePath)
        val shadowFiles = mutableListOf<String>()
        for (root in roots) {
            File(root, "package.json").takeIf { it.exists() }?.let { f -> names += parsePackageJson(f) }
            collectWorkspacePackageJsonFiles(root).forEach { f -> names += parsePackageJson(f) }
            File(root, "shadow-cljs.edn").takeIf { it.exists() }?.let { f ->
                val fromShadow = ShadowNpmDepsParser.collectNpmDepPackageNames(f)
                if (fromShadow.isNotEmpty()) shadowFiles += f.path
                names += fromShadow
            }
            if (settings.scanLockfileTransitive) {
                File(root, "package-lock.json").takeIf { it.exists() }?.let { names += parsePackageLockJson(it) }
            }
        }
        val out = names.filter { !it.startsWith("@types/") }.toSet()
        InteropDebugLog.debug(
            "[interop-npm-discover] roots=${roots.size} anchor=${anchorFilePath ?: "null"} " +
                "shadowEdnHits=${shadowFiles.size} packages=${out.size} " +
                "shadowPaths=${shadowFiles.take(4)}",
        )
        return out
    }

    private fun npmSignalsPresent(dir: File): Boolean =
        File(dir, "package.json").isFile ||
            File(dir, "shadow-cljs.edn").isFile ||
            (settings.scanLockfileTransitive && File(dir, "package-lock.json").isFile)

    private fun candidateNpmDiscoveryRoots(anchorFilePath: String?): List<File> {
        val seen = LinkedHashSet<File>()
        if (!anchorFilePath.isNullOrBlank()) {
            var d: File? = File(anchorFilePath).let { if (it.isFile) it.parentFile else it }
            var depth = 0
            while (d != null && depth++ < 64) {
                if (npmSignalsPresent(d)) seen.add(d)
                d = d.parentFile
            }
        }
        if (projectDir.isDirectory) {
            seen.add(projectDir)
        }
        return seen.toList()
    }

    private fun collectWorkspacePackageJsonFiles(workspaceRoot: File): List<File> {
        val roots = mutableListOf<File>()
        val packagesDir = File(workspaceRoot, "packages")
        if (packagesDir.isDirectory) {
            for (d in packagesDir.listFiles().orEmpty()) {
                val pj = File(d, "package.json")
                if (pj.isFile) roots += pj
            }
        }
        File(workspaceRoot, "package.json").takeIf { it.isFile }?.let { root ->
            try {
                val rootNode = mapper.readTree(root)
                val workspaces = rootNode.path("workspaces")
                if (workspaces.isArray) {
                    for (w in workspaces) {
                        if (w.isTextual) {
                            val pattern = w.asText()
                            expandWorkspaceGlob(pattern, workspaceRoot).forEach { roots += it }
                        }
                    }
                }
            } catch (_: Exception) { /* ignore */ }
        }
        return roots.distinct()
    }

    private fun expandWorkspaceGlob(pattern: String, workspaceRoot: File): List<File> {
        if (!pattern.contains("*")) {
            val f = File(workspaceRoot, pattern).resolve("package.json")
            return if (f.isFile) listOf(f) else emptyList()
        }
        val base = pattern.substringBefore("*").trimEnd('/')
        val dir = File(workspaceRoot, base)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.mapNotNull { sub ->
            File(sub, "package.json").takeIf { it.isFile }
        }.orEmpty()
    }

    private fun packageDirUnder(nodeModulesRoot: File, packageName: String): File {
        var dir = nodeModulesRoot
        for (part in packageName.split('/')) {
            if (part.isNotEmpty()) dir = File(dir, part)
        }
        return dir
    }

    private fun packageDirectory(packageName: String): File =
        packageDirUnder(nodeModules, packageName)

    /**
     * `node_modules` directories to search: walk up from a source file (workspace / nested installs),
     * then the project root's `node_modules`.
     */
    private fun candidateNodeModulesDirs(anchorFilePath: String?): List<File> {
        val seen = LinkedHashSet<File>()
        if (!anchorFilePath.isNullOrBlank()) {
            var d: File? = File(anchorFilePath).let { if (it.isFile) it.parentFile else it }
            var depth = 0
            while (d != null && depth++ < 48) {
                val nm = File(d, "node_modules")
                if (nm.isDirectory) seen.add(nm)
                d = d.parentFile
            }
        }
        if (nodeModules.isDirectory) seen.add(nodeModules)
        return seen.toList()
    }

    private fun atTypesDirUnder(nodeModulesRoot: File, packageName: String): File =
        File(nodeModulesRoot, "@types/${packageName.replace("/", "__")}")

    /**
     * Entry `.d.ts` for the package: `types` / `typings`, conditional `exports`, string `exports` next to `.d.ts`,
     * or root `index.d.ts` when present (many ESM packages omit top-level `types`).
     *
     * @param anchorFilePath optional absolute path of the cljs source (or any file under the repo); used to find
     * nested `node_modules` when dependencies are not hoisted to [projectDir]/node_modules.
     */
    fun typingsEntryFile(packageName: String, anchorFilePath: String? = null): File? {
        for (nmRoot in candidateNodeModulesDirs(anchorFilePath)) {
            val pkgDir = packageDirUnder(nmRoot, packageName)
            if (!pkgDir.isDirectory) continue
            val rel = findPackageOwnTypes(pkgDir) ?: continue
            val f = File(pkgDir, rel)
            if (f.isFile) return f
        }
        for (nmRoot in candidateNodeModulesDirs(anchorFilePath)) {
            val typesDir = atTypesDirUnder(nmRoot, packageName)
            if (!typesDir.isDirectory) continue
            val idx = File(typesDir, "index.d.ts")
            if (idx.isFile) return idx
        }
        return null
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

    private fun findPackageOwnTypes(pkgDir: File): String? =
        NpmPackageTypings.typingsEntryRelativePath(pkgDir)

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
