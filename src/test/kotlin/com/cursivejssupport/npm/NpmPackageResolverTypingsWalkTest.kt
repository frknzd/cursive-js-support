package com.cursivejssupport.npm

import com.cursivejssupport.settings.JsSupportSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
