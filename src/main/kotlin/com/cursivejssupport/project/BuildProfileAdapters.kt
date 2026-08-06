package com.cursivejssupport.project

import java.io.File

class ShadowCljsBuildProfileAdapter : CljsBuildProfileAdapter {
    override fun discover(projectRoot: File): List<CljsBuildProfile> =
        shadowConfigFiles(projectRoot).flatMap { config ->
            // The root config keeps the unqualified `shadow:<build>` id it has always had; a
            // module's config qualifies its ids so two modules declaring `:app` both survive
            // the `distinctBy { it.id }` in CljsProjectModel.
            discoverIn(config, qualifier = config.parentFile?.takeIf { it != projectRoot }?.name)
        }

    /**
     * `shadow-cljs.edn` at the project root, plus one per module in a monorepo. shadow-cljs
     * resolves every path in a config against that config's own directory, so each file yields
     * its own profiles rather than being merged into the root's.
     */
    private fun shadowConfigFiles(projectRoot: File): List<File> {
        val root = File(projectRoot, "shadow-cljs.edn").takeIf { it.isFile }
        val nested = projectRoot.walkTopDown()
            .maxDepth(SEARCH_DEPTH)
            .onEnter { it == projectRoot || it.name !in SKIPPED_DIRECTORIES }
            .filter { it.isFile && it.name == "shadow-cljs.edn" && it != root }
            .sortedBy { it.path }
        return listOfNotNull(root) + nested
    }

    private fun discoverIn(config: File, qualifier: String?): List<CljsBuildProfile> {
        // Every relative path in the config — `:source-paths`, `:output-to` — is relative to the
        // directory holding it, which is the project root only for a single-module project.
        val projectRoot = config.parentFile ?: return emptyList()
        val root = EdnBuildConfig.map(EdnBuildConfig.parse(config)) ?: return emptyList()
        val builds = EdnBuildConfig.map(EdnBuildConfig.value(root, "builds")) ?: return emptyList()
        // shadow-cljs `:source-paths` lives at the top level and applies to every build.
        val sourcePaths = EdnBuildConfig.stringList(EdnBuildConfig.value(root, "source-paths"))
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
                id = if (qualifier == null) "shadow:$id" else "shadow:$qualifier:$id",
                displayName = if (qualifier == null) "shadow-cljs $id" else "shadow-cljs $qualifier $id",
                tool = CljsBuildTool.SHADOW_CLJS,
                target = target,
                workingDirectory = projectRoot.absolutePath,
                outputDirectory = output,
                sourceMapRoots = listOfNotNull(output),
                launchCommand = listOf("npx", "shadow-cljs", "watch", id),
                browserUrl = browserUrl,
                inspectPort = if (target == CljsRuntimeTarget.NODE) 9229 else null,
                configFile = config.absolutePath,
                sourcePaths = sourcePaths,
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
        val sourcePaths = EdnBuildConfig.stringList(EdnBuildConfig.value(opts, "source-paths"))
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
            sourcePaths = sourcePaths,
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
            val sourcePaths = EdnBuildConfig.stringList(EdnBuildConfig.value(opts, "source-paths"))
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
                sourcePaths = sourcePaths,
            )
        }
    }
}

/** How far below the project root a build config is looked for — deep enough for one monorepo
 *  module level plus its own nesting, shallow enough not to walk a whole source tree. */
internal const val SEARCH_DEPTH = 3

/** Directories that never hold a hand-written build config and are expensive to walk. */
internal val SKIPPED_DIRECTORIES = setOf("node_modules", "target", "out", ".git", ".shadow-cljs", ".idea")

private fun cljsBuildFiles(root: File): List<File> =
    root.walkTopDown()
        .maxDepth(SEARCH_DEPTH)
        .onEnter { it == root || it.name !in SKIPPED_DIRECTORIES }
        .filter { it.isFile && it.name.endsWith(".cljs.edn") }
        .toList()
        .sortedBy { it.path }

private fun isFigwheelProject(root: File): Boolean {
    if (File(root, "figwheel-main.edn").isFile) return true
    val deps = File(root, "deps.edn").takeIf { it.isFile }?.readText().orEmpty()
    return "figwheel-main" in deps || "figwheel.main" in deps
}
