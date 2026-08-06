package com.cursivejssupport.npm

import com.cursivejssupport.project.CljsProjectModel
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import java.io.File

/**
 * Resolves a relative `(:require ["./foo.js" :as foo])` / `["/abs/path.js" …]` string to its target
 * `.js`/`.mjs`/`.cjs`/`.jsx` VirtualFile, mirroring how shadow-cljs and cljs.main resolve relative
 * JS imports.
 *
 * Resolution order:
 *  1. **Namespace-relative** (shadow-cljs default): the path is resolved against the directory
 *     of the requiring `.cljs` file — `./capture_util.js` next to `capture_util.cljs`.
 *  2. **Source-path-relative fallback**: if the namespace-relative lookup misses, try resolving
 *     the path against every build profile `source-paths` root (so `./scripts/util.js` can also
 *     resolve from a source root when the requiring file is elsewhere).
 *  3. **Absolute**: paths starting with `/` are resolved from the project root.
 *
 * Returns the first existing file with a JS-ish extension.
 */
object RelativeModuleResolver {
    private val log = logger<RelativeModuleResolver>()
    private val jsExtensions = listOf("js", "mjs", "cjs", "jsx", "ts", "tsx")

    fun resolve(file: PsiFile, requirePath: String): VirtualFile? {
        val project = file.project
        val requiringFile = file.virtualFile ?: return null
        val normalized = requirePath.replace('\\', '/').substringBefore('?')

        if (normalized.startsWith("/")) {
            val root = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return null
            val rel = normalized.removePrefix("/")
            return resolveAsFile(root, rel) ?: root.takeIf { it.isDirectory }?.let { VfsUtil.findRelativeFile(rel, it) }
        }

        // 1. Namespace-relative: resolve against the requiring file's directory.
        requiringFile.parent?.let { parent ->
            resolveRelative(parent, normalized)?.let { return it }
        }

        // 2. Source-path-relative fallback.
        val model = project.service<CljsProjectModel>()
        for (profile in model.profiles) {
            for (src in profile.sourcePaths) {
                val srcDir = resolveSourceDir(project, src, profile.workingDirectory) ?: continue
                resolveRelative(srcDir, normalized)?.let { return it }
            }
        }

        return null
    }

    private fun resolveRelative(base: VirtualFile, relative: String): VirtualFile? {
        val clean = relative.removePrefix("./")
        // Try the exact path first (with extension).
        VfsUtil.findRelativeFile(clean, base)?.let { if (!it.isDirectory) return it }
        // Try adding JS extensions if the path has none.
        if (!clean.contains('.')) {
            for (ext in jsExtensions) {
                VfsUtil.findRelativeFile("$clean.$ext", base)?.let { if (!it.isDirectory) return it }
            }
        }
        // Try as a directory with an index file.
        VfsUtil.findRelativeFile(clean, base)?.let { dir ->
            if (dir.isDirectory) resolveAsFile(dir, "")?.let { return it }
        }
        return null
    }

    private fun resolveAsFile(dir: VirtualFile, childPath: String): VirtualFile? {
        if (childPath.isNotEmpty()) {
            VfsUtil.findRelativeFile(childPath, dir)?.let { if (!it.isDirectory) return it }
        }
        for (ext in jsExtensions) {
            dir.findChild("index.$ext")?.let { if (!it.isDirectory) return it }
        }
        return null
    }

    private fun resolveSourceDir(project: Project, sourcePath: String, workingDirectory: String): VirtualFile? {
        val javaFile = File(sourcePath).let { if (it.isAbsolute) it else File(File(workingDirectory), sourcePath) }.normalize()
        return LocalFileSystem.getInstance().findFileByIoFile(javaFile)
    }
}
