package com.cursivejssupport.debug

import com.cursivejssupport.project.CljsBuildProfile
import com.cursivejssupport.project.SourceMapPathMapping
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import java.io.File

internal data class CljsDebugOverrides(
    val roots: String,
    val mappings: String,
    val workingDirectory: String,
    val command: String,
) {
    fun applyTo(profile: CljsBuildProfile): CljsBuildProfile = profile.copy(
        workingDirectory = workingDirectory.ifBlank { profile.workingDirectory },
        sourceMapRoots = parseRoots().ifEmpty { profile.sourceMapRoots },
        pathMappings = parseMappings().ifEmpty { profile.pathMappings },
        launchCommand = command.takeIf(String::isNotBlank)?.let(ParametersListUtil::parse) ?: profile.launchCommand,
    )

    private fun parseRoots() = roots.split(File.pathSeparatorChar, ';', '\n').map(String::trim).filter(String::isNotEmpty)
    private fun parseMappings() = mappings.lineSequence().mapNotNull { line ->
        line.split('=', limit = 2).takeIf { it.size == 2 && it.all(String::isNotBlank) }
            ?.let { SourceMapPathMapping(it[0].trim(), it[1].trim()) }
    }.toList()
}

internal object CljsDebugProcessLauncher {
    fun launch(
        project: Project,
        profile: CljsBuildProfile,
        environment: ExecutionEnvironment,
        requireCommand: Boolean = false,
        runtimeTransform: (List<String>) -> List<String> = { it },
    ) {
        if (profile.launchCommand.isEmpty() && !requireCommand) return
        var handler = start(profile.launchCommand, profile)
        profile.runtimeCommand?.let { runtime ->
            if (!handler.waitFor(60_000)) {
                handler.destroyProcess()
                throw ExecutionException("Timed out compiling '${profile.displayName}' before starting Node")
            }
            if (handler.exitCode != 0) throw ExecutionException("ClojureScript compiler exited with code ${handler.exitCode}")
            handler = start(runtimeTransform(runtime), profile)
        }
        val connection = project.messageBus.connect()
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processTerminated(id: String, env: ExecutionEnvironment, process: ProcessHandler, code: Int) {
                if (env !== environment) return
                if (!handler.isProcessTerminated) handler.destroyProcess()
                connection.disconnect()
            }
        })
    }

    private fun start(arguments: List<String>, profile: CljsBuildProfile): KillableProcessHandler {
        if (arguments.isEmpty()) throw ExecutionException("No build command is configured")
        return KillableProcessHandler(
            GeneralCommandLine(arguments).withWorkDirectory(profile.workingDirectory)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE),
        ).also(ProcessHandler::startNotify)
    }
}
