package com.cursivejssupport.npm

import com.cursivejssupport.settings.JsSupportSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NpmPackageResolverTypingsWalkTest {

    @Test
    fun `typingsEntryFile walks up from nested source to workspace node_modules`() {
        val repo = Files.createTempDirectory("npm-walk").toFile()
        try {
            val app = File(repo, "ai.app").apply { mkdirs() }
            val src = File(app, "src/views.cljs").apply { parentFile?.mkdirs(); writeText("") }
            val pkgDir = File(app, "node_modules/react-markdown").apply { mkdirs() }
            File(pkgDir, "package.json").writeText(
                """{"name":"react-markdown","type":"module","exports":"./index.js"}""",
            )
            File(pkgDir, "index.js").writeText("export {}\n")
            File(pkgDir, "index.d.ts").writeText("declare const x: string\nexport default x\n")

            val resolver = NpmPackageResolver(repo, JsSupportSettings.State())
            assertNull(
                "root node_modules has no package — without anchor this should miss",
                resolver.typingsEntryFile("react-markdown", anchorFilePath = null),
            )
            assertEquals(
                File(pkgDir, "index.d.ts"),
                resolver.typingsEntryFile("react-markdown", anchorFilePath = src.absolutePath),
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `at types provider resolves public package subpath`() {
        val repo = Files.createTempDirectory("npm-walk").toFile()
        try {
            File(repo, "package.json").writeText("""{"dependencies":{"react":"*"}}""")
            File(repo, "node_modules/react").mkdirs()
            val types = File(repo, "node_modules/@types/react").apply { mkdirs() }
            File(types, "package.json").writeText(
                """{"name":"@types/react","exports":{".":{"types":"./index.d.ts"},"./jsx-runtime":{"types":"./jsx-runtime.d.ts"}}}""",
            )
            File(types, "index.d.ts").writeText("export = React\n")
            File(types, "jsx-runtime.d.ts").writeText("export function jsx(): unknown\n")

            val resolver = NpmPackageResolver(repo, JsSupportSettings.State())
            assertEquals(
                File(types, "jsx-runtime.d.ts"),
                resolver.typingsEntryFile("react/jsx-runtime"),
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `javascript only package exposes every conditional entry`() {
        val repo = Files.createTempDirectory("npm-walk").toFile()
        try {
            File(repo, "package.json").writeText("""{"dependencies":{"plain-js":"*"}}""")
            val pkg = File(repo, "node_modules/plain-js").apply { mkdirs() }
            File(pkg, "package.json").writeText(
                """{"name":"plain-js","exports":{".":{"import":"./index.mjs","require":"./index.cjs"}}}""",
            )
            File(pkg, "index.mjs").writeText("export const esmOnly = true\n")
            File(pkg, "index.cjs").writeText("exports.cjsOnly = true\n")

            val resolved = NpmPackageResolver(repo, JsSupportSettings.State()).resolvePackage("plain-js")!!
            assertEquals(
                setOf(File(pkg, "index.mjs").absolutePath, File(pkg, "index.cjs").absolutePath),
                resolved.entryFiles.toSet(),
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `relative javascript specifiers prefer sibling declarations`() {
        val repo = Files.createTempDirectory("npm-walk").toFile()
        try {
            File(repo, "package.json").writeText("""{"dependencies":{"diff":"*"}}""")
            val pkg = File(repo, "node_modules/diff").apply { mkdirs() }
            File(pkg, "package.json").writeText("""{"name":"diff","types":"index.d.ts"}""")
            File(pkg, "index.d.ts").writeText("export { diffArrays } from './array.js'\n")
            File(pkg, "array.js").writeText("export function diffArrays() {}\n")
            File(pkg, "array.d.ts").writeText(
                "import type { ChangeObject } from './types.js'\n" +
                    "export type ImportedChange = import('./imported.js').ImportedChange\n" +
                    "export function diffArrays(): ChangeObject[]\n",
            )
            File(pkg, "types.js").writeText("export {}\n")
            File(pkg, "types.d.ts").writeText("export interface ChangeObject { added: boolean; count: number }\n")
            File(pkg, "imported.d.ts").writeText("export interface ImportedChange { value: string }\n")

            val files = NpmPackageResolver(repo, JsSupportSettings.State()).resolvePackage("diff")!!.files.keys
            assertTrue(files.contains(File(pkg, "array.d.ts").absolutePath))
            assertTrue(files.contains(File(pkg, "types.d.ts").absolutePath))
            assertTrue(files.contains(File(pkg, "imported.d.ts").absolutePath))
        } finally {
            repo.deleteRecursively()
        }
    }
}
