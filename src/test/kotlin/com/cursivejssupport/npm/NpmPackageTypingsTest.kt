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
