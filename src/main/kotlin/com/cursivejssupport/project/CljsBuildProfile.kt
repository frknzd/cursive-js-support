package com.cursivejssupport.project

import java.io.File

enum class CljsBuildTool { SHADOW_CLJS, CLJS_MAIN, FIGWHEEL_MAIN }
enum class CljsRuntimeTarget { BROWSER, NODE, BUN, DENO }

data class SourceMapPathMapping(
    val remotePrefix: String,
    val localPath: String,
)

data class CljsBuildProfile(
    val id: String,
    val displayName: String,
    val tool: CljsBuildTool,
    val target: CljsRuntimeTarget,
    val workingDirectory: String,
    val outputDirectory: String? = null,
    val sourceMapRoots: List<String> = emptyList(),
    val launchCommand: List<String>,
    val runtimeCommand: List<String>? = null,
    val browserUrl: String? = null,
    val inspectHost: String = "127.0.0.1",
    val inspectPort: Int? = null,
    val pathMappings: List<SourceMapPathMapping> = emptyList(),
    val configFile: String? = null,
    /** Source directories the build compiles, as declared in the build config (shadow-cljs
     *  `:source-paths`, cljs.main/figwheel `--source-paths`/`:source-paths`). Paths are resolved
     *  against [workingDirectory]. Used to attribute a `.cljs` file to its build's runtime target
     *  so environment-aware completion/hover can filter Node/Bun/Deno globals. */
    val sourcePaths: List<String> = emptyList(),
) {
    fun resolvedWorkingDirectory(projectRoot: File): File =
        File(workingDirectory).let { if (it.isAbsolute) it else File(projectRoot, workingDirectory) }
}

interface CljsBuildProfileAdapter {
    fun discover(projectRoot: File): List<CljsBuildProfile>
}
