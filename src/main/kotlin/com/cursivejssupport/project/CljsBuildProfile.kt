package com.cursivejssupport.project

import java.io.File

enum class CljsBuildTool { SHADOW_CLJS, CLJS_MAIN, FIGWHEEL_MAIN }
enum class CljsRuntimeTarget { BROWSER, NODE }

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
) {
    fun resolvedWorkingDirectory(projectRoot: File): File =
        File(workingDirectory).let { if (it.isAbsolute) it else File(projectRoot, workingDirectory) }
}

interface CljsBuildProfileAdapter {
    fun discover(projectRoot: File): List<CljsBuildProfile>
}
