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
}
