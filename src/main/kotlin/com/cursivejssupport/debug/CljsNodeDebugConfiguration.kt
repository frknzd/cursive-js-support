package com.cursivejssupport.debug

import com.cursivejssupport.project.CljsBuildProfile
import com.cursivejssupport.project.CljsProjectModel
import com.cursivejssupport.project.CljsRuntimeTarget
import com.cursivejssupport.project.SourceMapPathMapping
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configuration.EmptyRunProfileState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.javascript.debugger.execution.RemoteUrlMappingBean
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.debugger.wip.JSRemoteDebugConfiguration
import org.jdom.Element
import org.jetbrains.debugger.DebuggableRunConfiguration
import java.io.File
import java.net.InetSocketAddress
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTextField

class CljsNodeDebugConfiguration(
    project: Project,
    private val cljsFactory: ConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<Element>(project, cljsFactory, name),
    DebuggableRunConfiguration,
    RunConfigurationWithSuppressedDefaultRunAction {

    var profileId = ""
    var startBuild = true
    var hostOverride = ""
    var portOverride = 0
    var sourceMapRootsOverride = ""
    var pathMappingsOverride = ""
    var workingDirectoryOverride = ""
    var launchCommandOverride = ""

    override fun getConfigurationEditor(): SettingsEditor<out com.intellij.execution.configurations.RunConfiguration> =
        CljsNodeDebugSettingsEditor(project)

    override fun checkConfiguration() {
        val profile = effectiveProfile() ?: throw RuntimeConfigurationError("Select a discovered Node ClojureScript build profile")
        if (profile.target != CljsRuntimeTarget.NODE) throw RuntimeConfigurationError("Select a Node build profile")
        if (!startBuild && profile.sourceMapRoots.none { root -> File(root).walkTopDown().any { it.isFile && it.extension == "map" } }) {
            throw RuntimeConfigurationError("No source maps were found. Compile the selected development build first.")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val profile = effectiveProfile() ?: throw ExecutionException("ClojureScript Node profile '$profileId' was not found")
        project.service<SourceMapRegistry>().configure(profile.sourceMapRoots, profile.pathMappings)
        if (startBuild) launchBuild(profile, environment)
        return EmptyRunProfileState.INSTANCE
    }

    override fun computeDebugAddress(state: RunProfileState): InetSocketAddress = remoteDelegate().computeDebugAddress(state)

    override fun createDebugProcess(
        socketAddress: InetSocketAddress,
        session: XDebugSession,
        executionResult: ExecutionResult?,
        environment: ExecutionEnvironment,
    ): XDebugProcess = remoteDelegate().createDebugProcess(socketAddress, session, executionResult, environment)

    private fun remoteDelegate(): JSRemoteDebugConfiguration = JSRemoteDebugConfiguration(project, cljsFactory, name).also { delegate ->
        delegate.host = hostOverride.ifBlank { effectiveProfile()?.inspectHost ?: "127.0.0.1" }
        delegate.port = portOverride.takeIf { it > 0 } ?: effectiveProfile()?.inspectPort ?: 9229
        delegate.mappings.addAll(effectiveProfile()?.pathMappings.orEmpty().map { RemoteUrlMappingBean(it.remotePrefix, it.localPath) })
    }

    private fun launchBuild(profile: CljsBuildProfile, environment: ExecutionEnvironment) {
        val port = portOverride.takeIf { it > 0 } ?: profile.inspectPort ?: 9229
        CljsDebugProcessLauncher.launch(project, profile, environment, requireCommand = true) { runtime ->
            runtime.map { if (it.startsWith("--inspect-brk=")) "--inspect-brk=$port" else it }
        }
    }

    private fun effectiveProfile(): CljsBuildProfile? = CljsProjectModel.getInstance(project).profiles
        .firstOrNull { it.id == profileId }?.let {
            CljsDebugOverrides(sourceMapRootsOverride, pathMappingsOverride, workingDirectoryOverride, launchCommandOverride).applyTo(it)
        }

    override fun writeExternal(element: Element) {
        super<LocatableConfigurationBase>.writeExternal(element)
        listOf(
            "profile" to profileId, "start" to startBuild.toString(), "host" to hostOverride,
            "port" to portOverride.toString(), "roots" to sourceMapRootsOverride, "mappings" to pathMappingsOverride,
            "workingDirectory" to workingDirectoryOverride, "command" to launchCommandOverride,
        ).forEach { (key, value) -> JDOMExternalizerUtil.writeField(element, "cljsNode.$key", value) }
    }

    override fun readExternal(element: Element) {
        super<LocatableConfigurationBase>.readExternal(element)
        fun field(key: String) = JDOMExternalizerUtil.readField(element, "cljsNode.$key").orEmpty()
        profileId = field("profile")
        startBuild = field("start").toBooleanStrictOrNull() ?: true
        hostOverride = field("host")
        portOverride = field("port").toIntOrNull() ?: 0
        sourceMapRootsOverride = field("roots")
        pathMappingsOverride = field("mappings")
        workingDirectoryOverride = field("workingDirectory")
        launchCommandOverride = field("command")
    }
}

private class CljsNodeDebugSettingsEditor(private val project: Project) : SettingsEditor<CljsNodeDebugConfiguration>() {
    private val profiles = JComboBox<String>()
    private val start = JCheckBox("Compile and launch Node before attaching", true)
    private val host = JTextField()
    private val port = JTextField()
    private val roots = JTextField()
    private val mappings = JTextField()
    private val workingDirectory = JTextField()
    private val command = JTextField()
    private val panel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Node build profile:", profiles).addComponent(start)
        .addLabeledComponent("Inspector host:", host).addLabeledComponent("Inspector port:", port)
        .addLabeledComponent("Source-map roots override:", roots)
        .addLabeledComponent("Path mappings (remote=local):", mappings)
        .addLabeledComponent("Working directory override:", workingDirectory)
        .addLabeledComponent("Build command override:", command).panel

    override fun resetEditorFrom(configuration: CljsNodeDebugConfiguration) {
        val model = CljsProjectModel.getInstance(project)
        model.refreshProfiles()
        profiles.removeAllItems()
        model.profiles.filter { it.target == CljsRuntimeTarget.NODE }.forEach { profiles.addItem(it.id) }
        profiles.selectedItem = configuration.profileId.ifBlank { model.profiles.firstOrNull { it.target == CljsRuntimeTarget.NODE }?.id }
        start.isSelected = configuration.startBuild
        host.text = configuration.hostOverride
        port.text = configuration.portOverride.takeIf { it > 0 }?.toString().orEmpty()
        roots.text = configuration.sourceMapRootsOverride
        mappings.text = configuration.pathMappingsOverride
        workingDirectory.text = configuration.workingDirectoryOverride
        command.text = configuration.launchCommandOverride
    }

    override fun applyEditorTo(configuration: CljsNodeDebugConfiguration) {
        configuration.profileId = profiles.selectedItem as? String ?: ""
        configuration.startBuild = start.isSelected
        configuration.hostOverride = host.text.trim()
        configuration.portOverride = port.text.trim().toIntOrNull() ?: 0
        configuration.sourceMapRootsOverride = roots.text.trim()
        configuration.pathMappingsOverride = mappings.text.trim()
        configuration.workingDirectoryOverride = workingDirectory.text.trim()
        configuration.launchCommandOverride = command.text.trim()
    }

    override fun createEditor(): JComponent = panel
}
