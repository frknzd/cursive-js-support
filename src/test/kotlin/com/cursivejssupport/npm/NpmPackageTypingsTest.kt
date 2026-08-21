package com.cursivejssupport.npm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NpmPackageTypingsTest {

    @Test
    fun `string exports to sibling index d ts`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText(
                """{"name":"fake-markdown","type":"module","exports":"./index.js"}""",
            )
            File(dir, "index.js").writeText("export {}\n")
            File(dir, "index.d.ts").writeText("declare const x: string\nexport default x\n")
            assertEquals("index.d.ts", NpmPackageTypings.typingsEntryRelativePath(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `exports dot object with types field`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText(
                """{"name":"x","exports":{".":{"types":"./dist/x.d.ts","default":"./dist/x.js"}}}""",
            )
            File(dir, "dist").mkdirs()
            File(dir, "dist/x.js").writeText("export {}\n")
            File(dir, "dist/x.d.ts").writeText("export declare function f(): void\n")
            assertEquals("dist/x.d.ts", NpmPackageTypings.typingsEntryRelativePath(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolves diff style nested import and require declarations`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText(
                """{"name":"diff","types":"libcjs/index.d.ts","exports":{".":{"import":{"types":"./libesm/index.d.ts","default":"./libesm/index.js"},"require":{"types":"./libcjs/index.d.ts","default":"./libcjs/index.js"}}}}""",
            )
            File(dir, "libcjs").mkdirs()
            File(dir, "libesm").mkdirs()
            File(dir, "libcjs/index.d.ts").writeText("export { diffArrays } from './diff/array.js'\n")
            File(dir, "libesm/index.d.ts").writeText("export { diffArrays } from './diff/array.js'\n")
            assertEquals("libcjs/index.d.ts", NpmPackageTypings.typingsEntryRelativePath(dir))
            assertEquals(
                setOf("libcjs/index.d.ts", "libesm/index.d.ts"),
                NpmPackageTypings.typingsEntryRelativePaths(dir).toSet(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `finds d cts beside a commonjs conditional export`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText(
                """{"name":"x","exports":{".":{"require":"./dist/index.cjs"}}}""",
            )
            File(dir, "dist").mkdirs()
            File(dir, "dist/index.cjs").writeText("module.exports = {}\n")
            File(dir, "dist/index.d.cts").writeText("export = value\n")
            assertEquals("dist/index.d.cts", NpmPackageTypings.typingsEntryRelativePath(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `commonjs and module exports prefer their matching declaration format`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText(
                """{"name":"x","exports":{".":{"import":"./index.mjs","require":"./index.cjs"}}}""",
            )
            listOf("index.mjs", "index.cjs", "index.d.ts", "index.d.mts", "index.d.cts").forEach {
                File(dir, it).writeText("export {}\n")
            }
            assertEquals(
                listOf("index.d.mts", "index.d.cts"),
                NpmPackageTypings.typingsEntryRelativePaths(dir),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `types field resolves extensionless files and directory indexes`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText("""{"name":"x","types":"types"}""")
            File(dir, "types").mkdirs()
            File(dir, "types/index.d.ts").writeText("export declare const x: string\n")
            assertEquals("types/index.d.ts", NpmPackageTypings.typingsEntryRelativePath(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `collects declarations from every custom condition`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText(
                """{"name":"x","exports":{".":{"browser":{"types":"./browser.d.ts"},"node":{"types":"./node.d.cts"},"react-server":{"types":"./server.d.mts"}}}}""",
            )
            listOf("browser.d.ts", "node.d.cts", "server.d.mts").forEach {
                File(dir, it).writeText("export const ${it.substringBefore('.')}: string\n")
            }
            assertEquals(
                setOf("browser.d.ts", "node.d.cts", "server.d.mts"),
                NpmPackageTypings.typingsEntryRelativePaths(dir).toSet(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `collects javascript entries when declarations are absent`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText(
                """{"name":"x","exports":{".":{"import":"./index.mjs","require":"./index.cjs","browser":"./browser.js"}}}""",
            )
            listOf("index.mjs", "index.cjs", "browser.js").forEach { File(dir, it).writeText("export {}\n") }
            assertEquals(
                setOf("index.mjs", "index.cjs", "browser.js"),
                NpmPackageTypings.runtimeEntryRelativePaths(dir).toSet(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `runtime fields resolve extensionless files and directory indexes`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText(
                """{"name":"x","module":"module","main":"runtime"}""",
            )
            File(dir, "module.mjs").writeText("export {}\n")
            File(dir, "runtime").mkdirs()
            File(dir, "runtime/index.cjs").writeText("module.exports = {}\n")
            assertEquals(
                listOf("module.mjs", "runtime/index.cjs"),
                NpmPackageTypings.runtimeEntryRelativePaths(dir),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `no typings hints returns null`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText("""{"name":"x"}""")
            assertNull(NpmPackageTypings.typingsEntryRelativePath(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolves exact and wildcard subpath exports`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText(
                """{"name":"x","exports":{"./feature":{"types":"./types/feature.d.ts"},"./plugins/*":{"types":"./types/plugins/*.d.ts"}}}""",
            )
            File(dir, "types/plugins").mkdirs()
            File(dir, "types/feature.d.ts").writeText("export declare const feature: boolean")
            File(dir, "types/plugins/a.d.ts").writeText("export declare const a: boolean")
            assertEquals("types/feature.d.ts", NpmPackageTypings.typingsEntryRelativePath(dir, "feature"))
            assertEquals("types/plugins/a.d.ts", NpmPackageTypings.typingsEntryRelativePath(dir, "plugins/a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolves types versions subpath`() {
        val dir = Files.createTempDirectory("npm-typings").toFile()
        try {
            File(dir, "package.json").writeText("""{"name":"x","typesVersions":{"*":{"feature":["types/feature"]}}}""")
            File(dir, "types").mkdirs()
            File(dir, "types/feature.d.ts").writeText("export declare const feature: boolean")
            assertEquals("types/feature.d.ts", NpmPackageTypings.typingsEntryRelativePath(dir, "feature"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
