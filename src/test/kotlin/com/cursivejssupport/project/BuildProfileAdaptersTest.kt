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

    @Test fun `shadow adapter captures source-paths`() = withTempDir { root ->
        File(root, "shadow-cljs.edn").writeText(
            """{:source-paths ["src" "scripts"]
               :builds {:worker {:target :node-script :output-dir "out/worker"}}}""",
        )
        val profile = ShadowCljsBuildProfileAdapter().discover(root).single()
        assertEquals(listOf("src", "scripts"), profile.sourcePaths)
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

    @Test fun `shadow adapter discovers a module config below the project root`() = withTempDir { root ->
        // A monorepo: the IDE project is opened at the root, the shadow build lives in a module.
        val module = File(root, "ai.ibis.product-tour").also { it.mkdirs() }
        File(module, "shadow-cljs.edn").writeText(
            """{:source-paths ["src/cljc" "scripts/src"]
               :builds {:capture {:target :node-script :output-to "scripts/out/capture.js"}}}""",
        )
        val profile = ShadowCljsBuildProfileAdapter().discover(root).single()
        assertEquals("shadow:ai.ibis.product-tour:capture", profile.id)
        assertEquals(CljsRuntimeTarget.NODE, profile.target)
        // Paths in the config are relative to the config's own directory, not the project root.
        assertEquals(module.absolutePath, profile.workingDirectory)
        assertEquals(listOf("src/cljc", "scripts/src"), profile.sourcePaths)
    }

    @Test fun `shadow adapter keeps same-named builds from different modules apart`() = withTempDir { root ->
        for (name in listOf("frontend", "backend")) {
            val module = File(root, name).also { it.mkdirs() }
            File(module, "shadow-cljs.edn").writeText("""{:builds {:app {:target :browser}}}""")
        }
        val ids = ShadowCljsBuildProfileAdapter().discover(root).map { it.id }.toSet()
        assertEquals(setOf("shadow:backend:app", "shadow:frontend:app"), ids)
    }

    @Test fun `shadow adapter ignores configs inside node_modules`() = withTempDir { root ->
        val vendored = File(root, "node_modules/some-lib").also { it.mkdirs() }
        File(vendored, "shadow-cljs.edn").writeText("""{:builds {:app {:target :browser}}}""")
        assertTrue(ShadowCljsBuildProfileAdapter().discover(root).isEmpty())
    }

    @Test fun `a node-targeted module config attributes its own files`() = withTempDir { root ->
        val module = File(root, "tour").also { it.mkdirs() }
        File(module, "shadow-cljs.edn").writeText(
            """{:source-paths ["scripts/src"] :builds {:capture {:target :node-script}}}""",
        )
        val profile = ShadowCljsBuildProfileAdapter().discover(root).single()
        // What CljsProjectModel.runtimeTargetsForFile does with the profile: a file under the
        // module's own source path must resolve against the module, not the project root.
        val srcDir = File(profile.resolvedWorkingDirectory(root), profile.sourcePaths.single())
        assertEquals(File(module, "scripts/src").absolutePath, srcDir.absolutePath)
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("cljs-build-profile").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
