package com.cursivejssupport.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuildProfileAdaptersTest {
    @Test fun `shadow adapter discovers browser and node builds`() = withTempDir { root ->
        File(root, "shadow-cljs.edn").writeText(
            """{:builds {:app {:target :browser :output-dir "public/js" :devtools {:http-port 8080}}
               :worker {:target :node-script :output-dir "out/worker"}}}""",
        )
        val profiles = ShadowCljsBuildProfileAdapter().discover(root)
        assertEquals(2, profiles.size)
        assertEquals(CljsRuntimeTarget.BROWSER, profiles.first { it.id == "shadow:app" }.target)
        assertEquals("http://localhost:8080", profiles.first { it.id == "shadow:app" }.browserUrl)
        assertEquals(CljsRuntimeTarget.NODE, profiles.first { it.id == "shadow:worker" }.target)
    }

    @Test fun `cljs main adapter discovers compiler edn`() = withTempDir { root ->
        File(root, "dev.cljs.edn").writeText("{:target :nodejs :output-dir \"target/out\"}")
        val profile = CljsMainBuildProfileAdapter().discover(root).single()
        assertEquals("cljs-main:dev", profile.id)
        assertEquals(CljsRuntimeTarget.NODE, profile.target)
        assertTrue(profile.launchCommand.contains("cljs.main"))
    }

    @Test fun `figwheel claims cljs build files when dependency is present`() = withTempDir { root ->
        File(root, "deps.edn").writeText("{:deps {com.bhauman/figwheel-main {:mvn/version \"0.2.20\"}}}")
        File(root, "dev.cljs.edn").writeText("{:target :browser :output-dir \"target/public\"}")
        assertTrue(CljsMainBuildProfileAdapter().discover(root).isEmpty())
        assertEquals("figwheel:dev", FigwheelMainBuildProfileAdapter().discover(root).single().id)
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("cljs-build-profile").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
