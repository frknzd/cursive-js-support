package com.cursivejssupport.project

import java.io.File

class ShadowCljsBuildProfileAdapter : CljsBuildProfileAdapter {
    override fun discover(projectRoot: File): List<CljsBuildProfile> {
        val config = File(projectRoot, "shadow-cljs.edn").takeIf { it.isFile } ?: return emptyList()
        val root = EdnBuildConfig.map(EdnBuildConfig.parse(config)) ?: return emptyList()
        val builds = EdnBuildConfig.map(EdnBuildConfig.value(root, "builds")) ?: return emptyList()
        return builds.entries.mapNotNull { (rawId, rawBuild) ->
            val id = EdnBuildConfig.text(rawId) ?: return@mapNotNull null
            val build = EdnBuildConfig.map(rawBuild) ?: return@mapNotNull null
            val targetName = EdnBuildConfig.text(EdnBuildConfig.value(build, "target")).orEmpty()
            val target = if (targetName.contains("node")) CljsRuntimeTarget.NODE else CljsRuntimeTarget.BROWSER
            val outputTo = EdnBuildConfig.absolute(projectRoot, EdnBuildConfig.text(EdnBuildConfig.value(build, "output-to")))
            val output = EdnBuildConfig.absolute(projectRoot, EdnBuildConfig.text(EdnBuildConfig.value(build, "output-dir")))
                ?: outputTo?.let { File(it).parent }
            val devtools = EdnBuildConfig.map(EdnBuildConfig.value(build, "devtools"))
            val httpPort = EdnBuildConfig.text(EdnBuildConfig.value(devtools, "http-port"))?.toIntOrNull()
            val browserUrl = EdnBuildConfig.text(EdnBuildConfig.value(devtools, "open-url"))
                ?: httpPort?.let { "http://localhost:$it" }
            CljsBuildProfile(
                id = "shadow:$id",
                displayName = "shadow-cljs $id",
                tool = CljsBuildTool.SHADOW_CLJS,
                target = target,
                workingDirectory = projectRoot.absolutePath,
                outputDirectory = output,
                sourceMapRoots = listOfNotNull(output),
                launchCommand = listOf("npx", "shadow-cljs", "watch", id),
                browserUrl = browserUrl,
                inspectPort = if (target == CljsRuntimeTarget.NODE) 9229 else null,
                configFile = config.absolutePath,
            )
        }
    }
}

class CljsMainBuildProfileAdapter : CljsBuildProfileAdapter {
    override fun discover(projectRoot: File): List<CljsBuildProfile> {
        if (isFigwheelProject(projectRoot)) return emptyList()
        return cljsBuildFiles(projectRoot).mapNotNull { config -> profile(projectRoot, config) }
    }

    private fun profile(root: File, config: File): CljsBuildProfile? {
        val opts = EdnBuildConfig.map(EdnBuildConfig.parse(config)) ?: return null
        val targetName = EdnBuildConfig.text(EdnBuildConfig.value(opts, "target")).orEmpty()
        val target = if (targetName.contains("node")) CljsRuntimeTarget.NODE else CljsRuntimeTarget.BROWSER
        val output = EdnBuildConfig.absolute(root, EdnBuildConfig.text(EdnBuildConfig.value(opts, "output-dir")))
        val outputTo = EdnBuildConfig.absolute(root, EdnBuildConfig.text(EdnBuildConfig.value(opts, "output-to")))
        val main = EdnBuildConfig.text(EdnBuildConfig.value(opts, "main"))
        val id = config.name.removeSuffix(".cljs.edn").removeSuffix(".edn")
        return CljsBuildProfile(
            id = "cljs-main:$id",
            displayName = "cljs.main $id",
            tool = CljsBuildTool.CLJS_MAIN,
            target = target,
            workingDirectory = root.absolutePath,
            outputDirectory = output,
            sourceMapRoots = listOfNotNull(output),
            launchCommand = if (main != null) {
                listOf("clojure", "-M", "-m", "cljs.main", "-co", config.absolutePath, "-c", main)
            } else {
                listOf("clojure", "-M", "-m", "cljs.main", "-co", config.absolutePath, "-w", "src")
            },
            runtimeCommand = if (target == CljsRuntimeTarget.NODE && outputTo != null) {
                listOf("node", "--inspect-brk=9229", outputTo)
            } else null,
            pathMappings = if (output != null && File(root, "src").isDirectory) {
                listOf(SourceMapPathMapping(output, File(root, "src").absolutePath))
            } else emptyList(),
            browserUrl = if (target == CljsRuntimeTarget.BROWSER) "http://localhost:9000" else null,
            inspectPort = if (target == CljsRuntimeTarget.NODE) 9229 else null,
            configFile = config.absolutePath,
        )
    }
}

class FigwheelMainBuildProfileAdapter : CljsBuildProfileAdapter {
    override fun discover(projectRoot: File): List<CljsBuildProfile> {
        if (!isFigwheelProject(projectRoot)) return emptyList()
        return cljsBuildFiles(projectRoot).map { config ->
            val opts = EdnBuildConfig.map(EdnBuildConfig.parse(config))
            val targetName = EdnBuildConfig.text(EdnBuildConfig.value(opts, "target")).orEmpty()
            val target = if (targetName.contains("node")) CljsRuntimeTarget.NODE else CljsRuntimeTarget.BROWSER
            val id = config.name.removeSuffix(".cljs.edn")
            val output = EdnBuildConfig.absolute(projectRoot, EdnBuildConfig.text(EdnBuildConfig.value(opts, "output-dir")))
            CljsBuildProfile(
                id = "figwheel:$id",
                displayName = "Figwheel Main $id",
                tool = CljsBuildTool.FIGWHEEL_MAIN,
                target = target,
                workingDirectory = projectRoot.absolutePath,
                outputDirectory = output,
                sourceMapRoots = listOfNotNull(output),
                launchCommand = listOf("clojure", "-M", "-m", "figwheel.main", "-b", id, "-r"),
                browserUrl = if (target == CljsRuntimeTarget.BROWSER) "http://localhost:9500" else null,
                inspectPort = if (target == CljsRuntimeTarget.NODE) 9229 else null,
                pathMappings = if (output != null && File(projectRoot, "src").isDirectory) {
                    listOf(SourceMapPathMapping(output, File(projectRoot, "src").absolutePath))
                } else emptyList(),
                configFile = config.absolutePath,
            )
        }
    }
}

private fun cljsBuildFiles(root: File): List<File> =
    root.walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".cljs.edn") }.toList().sortedBy { it.path }

private fun isFigwheelProject(root: File): Boolean {
    if (File(root, "figwheel-main.edn").isFile) return true
    val deps = File(root, "deps.edn").takeIf { it.isFile }?.readText().orEmpty()
    return "figwheel-main" in deps || "figwheel.main" in deps
}
