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

    @Test
    fun `discovers nested package-json in monorepo immediate subdirectories`() {
        val repo = Files.createTempDirectory("mono-disc").toFile()
        try {
            val projectRoot = File(repo, "project").apply { mkdirs() }
            val app1 = File(projectRoot, "app1").apply { mkdirs() }
            File(app1, "package.json").writeText("""{"dependencies": {"lodash": "1.0.0"}}""")
            
            val app2 = File(projectRoot, "app2").apply { mkdirs() }
            File(app2, "package.json").writeText("""{"dependencies": {"axios": "1.0.0"}}""")

            val resolver = NpmPackageResolver(projectRoot, JsSupportSettings.State())
            val names = resolver.discoverAllDependencyPackageNames(null)
            
            assertTrue("Should find lodash from app1", names.contains("lodash"))
            assertTrue("Should find axios from app2", names.contains("axios"))
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `discovers packages physically present in node_modules even if not in package-json`() {
        val repo = Files.createTempDirectory("nm-fallback").toFile()
        try {
            val root = File(repo, "project").apply { mkdirs() }
            val nm = File(root, "node_modules").apply { mkdirs() }
            File(nm, "react").apply { mkdirs() }
            File(nm, "@mui/material").apply { mkdirs() }
            
            val resolver = NpmPackageResolver(root, JsSupportSettings.State())
            val names = resolver.discoverAllDependencyPackageNames(null)
            
            assertTrue("Should find react from node_modules", names.contains("react"))
            assertTrue("Should find @mui/material from node_modules", names.contains("@mui/material"))
        } finally {
            repo.deleteRecursively()
        }
    }
}
