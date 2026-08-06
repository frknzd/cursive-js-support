package com.cursivejssupport.index

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Resolves TypeScript lib declaration files under bundled resource directories (`js/lib`,
 * `js/node-types`, `js/bun-types`, `js/deno-types`) to a directory on disk so [LocalFileSystem]
 * can open them. Handles both IDE/classes (`file:` URL) and packaged plugin (`jar:` URL).
 *
 * Each logical prefix maps to its own on-disk root so files with the same basename across
 * environments (e.g. `globals.d.ts` in both `@types/node` and `bun-types`) don't collide.
 */
object BundledDomLibs {

    private val log = logger<BundledDomLibs>()

    /** Logical path prefix stored in the prebuilt browser index (stable across machines). */
    const val LOGICAL_LIB_PREFIX = "js/lib/"
    const val NODE_TYPES_PREFIX = "js/node-types/"
    const val BUN_TYPES_PREFIX = "js/bun-types/"
    const val DENO_TYPES_PREFIX = "js/deno-types/"

    private val allPrefixes = listOf(LOGICAL_LIB_PREFIX, NODE_TYPES_PREFIX, BUN_TYPES_PREFIX, DENO_TYPES_PREFIX)

    /** Resource used as a sentinel to locate the bundled `js/` tree on the classpath. */
    private val sentinelResource = "js/lib/lib.dom.d.ts"

    @Volatile
    private var roots: Map<String, Path>? = null

    fun resolveVirtualFile(filePath: String): VirtualFile? {
        val normalized = filePath.replace('\\', '/')
        val lfs = LocalFileSystem.getInstance()

        val direct = File(filePath)
        if (direct.isAbsolute) {
            lfs.refreshAndFindFileByIoFile(direct)?.let { return it }
        }

        val prefix = allPrefixes.firstOrNull { normalized.startsWith(it) }
        val baseName = direct.name
        val relative = when (prefix) {
            null -> if (normalized.contains('/') && !direct.isAbsolute) normalized.substringAfterLast('/') else baseName
            else -> normalized.removePrefix(prefix)
        }

        val root = resolveRoots()[prefix ?: LOGICAL_LIB_PREFIX] ?: return null
        val target = root.resolve(relative).normalize()
        if (!target.startsWith(root)) return null
        val io = target.toFile()
        if (!io.isFile) return null
        return lfs.refreshAndFindFileByIoFile(io)
    }

    private fun resolveRoots(): Map<String, Path> {
        roots?.let { return it }
        synchronized(this) {
            roots?.let { return it }
            val cl = BundledDomLibs::class.java.classLoader
            val sample: URL = cl.getResource(sentinelResource)
                ?: run {
                    log.warn("Bundled lib.dom.d.ts not found on classpath")
                    return emptyMap()
                }
            val resolved = when (sample.protocol) {
                "file" -> resolveFileRoots(sample)
                "jar" -> resolveJarRoots(sample)
                else -> {
                    log.warn("Unsupported bundled lib URL protocol: ${sample.protocol}")
                    emptyMap()
                }
            }
            roots = resolved
            return resolved
        }
    }

    private fun resolveFileRoots(sample: URL): Map<String, Path> {
        // sample -> js/lib/lib.dom.d.ts; the js/ dir is two parents up from the file.
        val jsDir = File(sample.toURI()).parentFile?.parentFile ?: return emptyMap()
        return allPrefixes.mapNotNull { prefix ->
            val dir = jsDir.resolve(prefix.removeSuffix("/"))
            if (dir.isDirectory) prefix to dir.toPath() else null
        }.toMap()
    }

    private fun resolveJarRoots(sampleUrl: URL): Map<String, Path> = try {
        val conn = sampleUrl.openConnection() as JarURLConnection
        val jar = conn.jarFile
        val base = Files.createTempDirectory("cursive-js-bundled-libs-")
        val out = mutableMapOf<String, Path>()
        for (prefix in allPrefixes) {
            val dir = base.resolve(prefix.removeSuffix("/"))
            Files.createDirectories(dir)
            out[prefix] = dir
        }
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.isDirectory) continue
            val name = e.name
            val prefix = allPrefixes.firstOrNull { name.startsWith(it) && name.endsWith(".d.ts") } ?: continue
            val shortName = name.substring(prefix.length)
            if (shortName.contains('/')) continue
            val target = out[prefix]!!.resolve(shortName)
            jar.getInputStream(e).use { input ->
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        out
    } catch (e: Exception) {
        log.warn("Failed to extract bundled lib .d.ts files from jar", e)
        emptyMap()
    }
}
