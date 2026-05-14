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
 * Resolves TypeScript lib declaration files under `js/lib` (bundled resources) to a directory on disk
 * so [LocalFileSystem] can open them.
 * Handles both IDE/classes (`file:` URL) and packaged plugin (`jar:` URL).
 */
object BundledDomLibs {

    private val log = logger<BundledDomLibs>()

    @Volatile
    private var cachedRoot: Path? = null

    /** Logical path prefix stored in the prebuilt index (stable across machines). */
    const val LOGICAL_LIB_PREFIX = "js/lib/"

    fun resolveVirtualFile(filePath: String): VirtualFile? {
        val normalized = filePath.replace('\\', '/')
        val lfs = LocalFileSystem.getInstance()

        val direct = File(filePath)
        if (direct.isAbsolute) {
            lfs.refreshAndFindFileByIoFile(direct)?.let { return it }
        }

        val baseName = direct.name
        val relativeUnderLib = when {
            normalized.startsWith(LOGICAL_LIB_PREFIX) -> normalized.removePrefix(LOGICAL_LIB_PREFIX)
            normalized.contains('/') && !direct.isAbsolute -> normalized.substringAfterLast('/')
            else -> baseName
        }

        val root = resolveBundledLibRoot() ?: return null
        val target = root.resolve(relativeUnderLib).normalize()
        if (!target.startsWith(root)) return null
        val io = target.toFile()
        if (!io.isFile) return null
        return lfs.refreshAndFindFileByIoFile(io)
    }

    private fun resolveBundledLibRoot(): Path? {
        cachedRoot?.let { return it }
        synchronized(this) {
            cachedRoot?.let { return it }
            val cl = BundledDomLibs::class.java.classLoader
            val sample: URL = cl.getResource("js/lib/lib.dom.d.ts")
                ?: run {
                    log.warn("Bundled lib.dom.d.ts not found on classpath")
                    return null
                }
            val root = when (sample.protocol) {
                "file" -> File(sample.toURI()).parentFile?.toPath()
                "jar" -> extractJarLibsToTemp(sample)
                else -> {
                    log.warn("Unsupported bundled lib URL protocol: ${sample.protocol}")
                    null
                }
            }
            cachedRoot = root
            return root
        }
    }

    private fun extractJarLibsToTemp(sampleUrl: URL): Path? = try {
        val conn = sampleUrl.openConnection() as JarURLConnection
        val jar = conn.jarFile
        val dir = Files.createTempDirectory("cursive-js-bundled-libs-")
        val prefix = "js/lib/"
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.isDirectory) continue
            val name = e.name
            if (!name.startsWith(prefix) || !name.endsWith(".d.ts")) continue
            val shortName = name.substring(prefix.length)
            if (shortName.contains('/')) continue
            val out = dir.resolve(shortName)
            jar.getInputStream(e).use { input ->
                Files.copy(input, out, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        dir
    } catch (e: Exception) {
        log.warn("Failed to extract bundled lib .d.ts files from jar", e)
        null
    }
}
