package com.cursivejssupport.npm

import com.cursivejssupport.settings.JsSupportSettings
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NpmPackageResolverDiscoverWalkTest {

    @Test
    fun `discovers shadow npm-deps from ancestor of cljs file not at ide root`() {
        val repo = Files.createTempDirectory("disc-walk").toFile()
        try {
            val ideRoot = File(repo, "ide-root").apply { mkdirs() }
            val app = File(ideRoot, "apps/myapp").apply { mkdirs() }
            File(app, "shadow-cljs.edn").writeText(
                """{:builds {:app {:target :browser}} :npm-deps {"react" "18.2.0" "left-pad" "1.0.0"}}""",
            )
            val src = File(app, "src/views.cljs").apply { parentFile?.mkdirs(); writeText("(ns x)") }

            val resolver = NpmPackageResolver(ideRoot, JsSupportSettings.State())
            val withoutAnchor = resolver.discoverAllDependencyPackageNames(null)
            assertTrue("IDE root has no shadow.edn", withoutAnchor.isEmpty())

            val withAnchor = resolver.discoverAllDependencyPackageNames(src.absolutePath)
            assertTrue(withAnchor.contains("react"))
            assertTrue(withAnchor.contains("left-pad"))
        } finally {
            repo.deleteRecursively()
        }
    }
}
