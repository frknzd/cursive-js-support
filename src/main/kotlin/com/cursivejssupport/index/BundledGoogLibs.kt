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
 * Resolves Google Closure Library source files (`.js`) that are bundled under
 * `resources/js/lib/google-closure-library/` to a [VirtualFile] that IntelliJ
 * can open for Go-to-Declaration navigation.
 *
 * Paths stored in `goog-symbols.json.gz` use the logical prefix:
 *   `js/lib/google-closure-library/closure/goog/…`
 *
 * This class handles both:
 *  - **Dev / file mode** (`file:` URL): resolves directly from the on-disk
 *    `src/main/resources/js/lib/google-closure-library/` directory.
 *  - **Packaged / jar mode** (`jar:` URL): extracts the entire
 *    `js/lib/google-closure-library/` sub-tree from the plugin JAR to a temp
 *    directory on first access, preserving the full nested directory structure,
 *    and then serves subsequent lookups from the extracted cache.
 *
 * Sentinel file used to locate the root: `js/lib/google-closure-library/closure/goog/base.js`
 *
 * @see BundledDomLibs for the analogous resolver for TypeScript `.d.ts` lib files.
 */
object BundledGoogLibs {

    private val log = logger<BundledGoogLibs>()

    /** Logical path prefix stored in the prebuilt index — must stay in sync with
     *  the `BUNDLED_CLOSURE_PREFIX` constant in `scripts/build-goog-index.mjs`. */
    const val LOGICAL_PREFIX = "js/lib/google-closure-library/"

    /** Sentinel classpath resource used to locate the bundled root directory. */
    private const val SENTINEL_RESOURCE = "js/lib/google-closure-library/closure/goog/base.js"

    @Volatile
    private var cachedRoot: Path? = null

    /**
     * Resolves [filePath] (as stored in the index) to a [VirtualFile].
     * Returns `null` immediately if [filePath] does not start with [LOGICAL_PREFIX]
     * or if the underlying file cannot be located.
     */
    fun resolveVirtualFile(filePath: String): VirtualFile? {
        if (!filePath.startsWith(LOGICAL_PREFIX)) return null

        val relative = filePath.removePrefix(LOGICAL_PREFIX)
        val root = resolveBundledGoogRoot() ?: return null
        val target = root.resolve(relative).normalize()

        // Guard against path-traversal
        if (!target.startsWith(root)) return null

        val io = target.toFile()
        if (!io.isFile) return null

        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)
    }

    // ─── Root resolution ─────────────────────────────────────────────────────

    private fun resolveBundledGoogRoot(): Path? {
        cachedRoot?.let { return it }
        synchronized(this) {
            cachedRoot?.let { return it }

            val cl = BundledGoogLibs::class.java.classLoader
            val sample: URL = cl.getResource(SENTINEL_RESOURCE)
                ?: run {
                    log.warn("BundledGoogLibs: sentinel $SENTINEL_RESOURCE not found on classpath — " +
                            "run 'node scripts/build-goog-index.mjs' to bundle the closure source")
                    return null
                }

            val root: Path? = when (sample.protocol) {
                "file" -> {
                    // Dev mode: resources live on disk, root is LOGICAL_PREFIX away from sentinel.
                    // sentinel is at …/js/lib/google-closure-library/closure/goog/base.js
                    // root should be  …/js/lib/google-closure-library/
                    File(sample.toURI()).toPath()
                        .parent  // goog/
                        ?.parent // closure/
                        ?.parent // google-closure-library/
                }
                "jar" -> extractJarGoogToTemp(sample)
                else -> {
                    log.warn("BundledGoogLibs: unsupported URL protocol '${sample.protocol}'")
                    null
                }
            }

            cachedRoot = root
            return root
        }
    }

    // ─── JAR extraction ──────────────────────────────────────────────────────

    /**
     * Extracts all entries under `js/lib/google-closure-library/` from the plugin JAR
     * to a temp directory, preserving the full nested directory structure.
     *
     * Returns the path to the temp directory root (i.e. the directory that
     * represents `js/lib/google-closure-library/` itself), or `null` on failure.
     */
    private fun extractJarGoogToTemp(sampleUrl: URL): Path? = try {
        val conn = sampleUrl.openConnection() as JarURLConnection
        val jar = conn.jarFile
        val tempRoot = Files.createTempDirectory("cursive-js-goog-libs-")

        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name  // e.g. "js/lib/google-closure-library/closure/goog/base.js"
            if (!name.startsWith(LOGICAL_PREFIX)) continue

            // Relative path within the google-closure-library root,
            // e.g. "closure/goog/base.js"
            val relative = name.removePrefix(LOGICAL_PREFIX)

            val dest = tempRoot.resolve(relative).normalize()
            // Guard against path-traversal entries in the JAR
            if (!dest.startsWith(tempRoot)) continue

            Files.createDirectories(dest.parent)
            jar.getInputStream(entry).use { input ->
                Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        log.info("BundledGoogLibs: extracted closure source to $tempRoot")
        tempRoot
    } catch (e: Exception) {
        log.warn("BundledGoogLibs: failed to extract closure source from JAR", e)
        null
    }
}
