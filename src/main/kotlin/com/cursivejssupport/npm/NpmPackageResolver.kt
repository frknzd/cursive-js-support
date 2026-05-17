package com.cursivejssupport.npm

import com.cursivejssupport.settings.JsSupportSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ResolvedNpmPackage(
    val packageName: String,
    val files: Map<String, String>  // absolute file path → .d.ts content
)

@Service(Service.Level.PROJECT)
class NpmPackageResolver(
    private val project: Project?,
) {
    private var testProjectDir: File? = null
    private var testSettings: JsSupportSettings.State? = null

    private val projectDir: File get() = testProjectDir ?: File(project?.basePath ?: "")
    private val settings: JsSupportSettings.State get() = testSettings ?: JsSupportSettings.getInstance().state
    private val log = logger<NpmPackageResolver>()
    private val mapper = jacksonObjectMapper()
    private val nodeModules get() = File(projectDir, "node_modules")

    constructor(projectDir: File, settings: JsSupportSettings.State) : this(null) {
        this.testProjectDir = projectDir
        this.testSettings = settings
    }

    private val packageDiscoveryCache = ConcurrentHashMap<String, Set<String>>()

    fun resolveAll(): List<ResolvedNpmPackage> {
        val roots = candidateNpmDiscoveryRoots(null)
        val allNames = mutableSetOf<String>()
        for (root in roots) {
            allNames += discoverAllDependencyPackageNames(root.absolutePath)
        }

        return allNames.mapNotNull { name ->
            // Try to resolve the package by walking up from each discovery root
            roots.asSequence()
                .mapNotNull { root -> resolve(name, root.absolutePath) }
                .firstOrNull()
        }.also { log.info("Cursive JS Support: resolved types for ${it.size} npm packages") }
    }

    /**
     * All package names for completion / indexing: merges [package.json] deps, workspace package.json files,
     * [:npm-deps] from [shadow-cljs.edn], and optionally [package-lock.json], from the IDE project root and from
     * every directory up the chain from [anchorFilePath] that contains any of those files (nested CLJS apps).
     */
    fun discoverAllDependencyPackageNames(anchorFilePath: String? = null): Set<String> {
        val cacheKey = anchorFilePath ?: "PROJECT_ROOT"
        // Invalidate cache if too old or just return cached
        // For now, let's just return if present
        packageDiscoveryCache[cacheKey]?.let { return it }

        val names = mutableSetOf<String>()
        val roots = candidateNpmDiscoveryRoots(anchorFilePath)
        for (root in roots) {
            File(root, "package.json").takeIf { it.exists() }?.let { f -> names += parsePackageJson(f) }
            collectWorkspacePackageJsonFiles(root).forEach { f -> names += parsePackageJson(f) }
            File(root, "shadow-cljs.edn").takeIf { it.exists() }?.let { f ->
                names += ShadowNpmDepsParser.collectNpmDepPackageNames(f)
            }
            if (settings.scanLockfileTransitive) {
                File(root, "package-lock.json").takeIf { it.exists() }?.let { names += parsePackageLockJson(it) }
            }
            
            // Also include anything physically present in node_modules as a fallback
            val nm = File(root, "node_modules")
            if (nm.isDirectory) {
                names += listInstalledPackages(nm)
            }
        }
        
        // Include packages from IDE internal cache
        System.getProperty("idea.system.path")?.let { systemDir ->
            val cache = File(systemDir, "javascript/typings")
            if (cache.isDirectory) {
                cache.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { 
                    names += it.name 
                }
            }
        }

        val result = names.filter { !it.startsWith("@types/") }.toSet()
        packageDiscoveryCache[cacheKey] = result
        return result
    }

    fun clearCache() {
        packageDiscoveryCache.clear()
    }

    private fun listInstalledPackages(nodeModulesDir: File): Set<String> {
        val out = mutableSetOf<String>()
        nodeModulesDir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                if (f.name.startsWith("@")) {
                    f.listFiles()?.forEach { sub ->
                        if (sub.isDirectory) out += "${f.name}/${sub.name}"
                    }
                } else if (f.name != ".bin") {
                    out += f.name
                }
            }
        }
        return out
    }

    private fun npmSignalsPresent(dir: File): Boolean =
        File(dir, "package.json").isFile ||
            File(dir, "shadow-cljs.edn").isFile ||
            File(dir, "node_modules").isDirectory ||
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
        
        // Also look for workspace roots/monorepos from the project dir
        if (projectDir.isDirectory) {
            seen.add(projectDir)
            // Depth 2 search for nested package.json files (common in monorepos)
            projectDir.listFiles()?.forEach { sub ->
                if (sub.isDirectory && !sub.name.startsWith(".") && sub.name != "node_modules") {
                    if (npmSignalsPresent(sub)) seen.add(sub)
                }
            }
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
     * `node_modules` directories and IDE cache to search: walk up from a source file (workspace / nested installs),
     * then the project root's `node_modules`, then the IDE's internal typing cache.
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
        
        // IDE internal cache (system/javascript/typings)
        val systemDir = System.getProperty("idea.system.path")
        if (systemDir != null) {
            val cache = File(systemDir, "javascript/typings")
            if (cache.isDirectory) seen.add(cache)
        }

        return seen.toList()
    }

    private fun atTypesDirUnder(nodeModulesRoot: File, packageName: String): File {
        // Handle both standard @types and IntelliJ's cache structure
        if (nodeModulesRoot.name == "typings") {
            // IntelliJ cache usually has package/version/node_modules/@types/package
            val pkgDir = File(nodeModulesRoot, packageName)
            if (pkgDir.isDirectory) {
                // Find latest version or first one
                val latest = pkgDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.firstOrNull()
                if (latest != null) {
                    val atTypes = File(latest, "node_modules/@types/${packageName.replace("/", "__")}")
                    if (atTypes.isDirectory) return atTypes
                    // Sometimes it's just index.d.ts directly under version/node_modules/packageName
                    val direct = File(latest, "node_modules/$packageName")
                    if (direct.isDirectory) return direct
                }
            }
        }
        return File(nodeModulesRoot, "@types/${packageName.replace("/", "__")}")
    }

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

    private fun resolve(packageName: String, anchorFilePath: String? = null): ResolvedNpmPackage? {
        val files = mutableMapOf<String, String>()

        var foundPkgDir: File? = null
        for (nmRoot in candidateNodeModulesDirs(anchorFilePath)) {
            val d = packageDirUnder(nmRoot, packageName)
            if (d.isDirectory) {
                foundPkgDir = d
                break
            }
        }

        if (foundPkgDir != null) {
            val entryDts = findPackageOwnTypes(foundPkgDir)
            if (entryDts != null) {
                files.putAll(collectDtsFiles(foundPkgDir, entryDts))
            }
        }

        if (files.isEmpty()) {
            for (nmRoot in candidateNodeModulesDirs(anchorFilePath)) {
                val typesDir = atTypesDirUnder(nmRoot, packageName)
                if (typesDir.isDirectory) {
                    val index = File(typesDir, "index.d.ts")
                    if (index.isFile) {
                        files.putAll(collectDtsFiles(typesDir, "index.d.ts"))
                        break
                    }
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
