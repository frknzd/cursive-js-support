package com.cursivejssupport.debug

import com.cursivejssupport.project.CljsBuildProfile
import com.cursivejssupport.project.CljsProjectModel
import com.cursivejssupport.project.CljsRuntimeTarget
import com.cursivejssupport.project.SourceMapPathMapping
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.javascript.debugger.execution.JavaScriptDebugConfiguration
import com.intellij.javascript.debugger.execution.RemoteUrlMappingBean
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.util.ui.FormBuilder
import com.intellij.util.execution.ParametersListUtil
import org.jdom.Element
import java.io.File
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTextField

class CljsDebugConfigurationType : ConfigurationTypeBase(
    "ClojureScriptJavaScriptDebug",
    "ClojureScript JavaScript Debug",
    "Debug compiled ClojureScript through JavaScript source maps",
    AllIcons.Nodes.Module,
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun createTemplateConfiguration(project: Project) =
                CljsDebugConfiguration(project, this, "ClojureScript Browser Debug")

            override fun getId(): String = "ClojureScriptBrowserDebugFactory"
            override fun getName(): String = "Browser"
        })
        addFactory(object : ConfigurationFactory(this) {
            override fun createTemplateConfiguration(project: Project) =
                CljsNodeDebugConfiguration(project, this, "ClojureScript Node Debug")

            override fun getId(): String = "ClojureScriptNodeDebugFactory"
            override fun getName(): String = "Node"
        })
    }
}

class CljsDebugConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : JavaScriptDebugConfiguration(project, factory, name) {

    var profileId: String = ""
    var startBuild: Boolean = true
    var overrideUrl: String = ""
    var overrideHost: String = ""
    var overridePort: Int = 0
    var sourceMapRootsOverride: String = ""
    var pathMappingsOverride: String = ""
    var workingDirectoryOverride: String = ""
    var launchCommandOverride: String = ""

    override fun getConfigurationEditor(): SettingsEditor<out com.intellij.execution.configurations.RunConfiguration> =
        CljsDebugSettingsEditor(project)

    override fun checkConfiguration() {
        val profile = effectiveProfile() ?: throw RuntimeConfigurationError("Select a discovered ClojureScript build profile")
        if (profile.target != CljsRuntimeTarget.BROWSER) throw RuntimeConfigurationError("Select a browser build profile; use the Node factory for Node targets")
        val endpoint = endpoint(profile)
        if (endpoint.isBlank()) throw RuntimeConfigurationError("Configure a browser URL or Node inspect endpoint")
        if (!startBuild && profile.sourceMapRoots.none { root -> File(root).walkTopDown().any { it.isFile && it.extension == "map" } }) {
            throw RuntimeConfigurationError("No source maps were found. Compile the selected development build first.")
        }
        uri = endpoint
        mappings = profile.pathMappings.map { RemoteUrlMappingBean(it.remotePrefix, it.localPath) }
        setAllowSourceMapHeavyProcessing(true)
        project.service<SourceMapRegistry>().configure(profile.sourceMapRoots, profile.pathMappings)
        super.checkConfiguration()
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val profile = effectiveProfile() ?: throw ExecutionException("ClojureScript build profile '$profileId' was not found")
        uri = endpoint(profile)
        mappings = profile.pathMappings.map { RemoteUrlMappingBean(it.remotePrefix, it.localPath) }
        setAllowSourceMapHeavyProcessing(true)
        project.service<SourceMapRegistry>().configure(profile.sourceMapRoots, profile.pathMappings)
        if (startBuild) launchBuild(profile, environment)
        return super.getState(executor, environment)
    }

    private fun launchBuild(profile: CljsBuildProfile, environment: ExecutionEnvironment) =
        CljsDebugProcessLauncher.launch(project, profile, environment)

    private fun endpoint(profile: CljsBuildProfile): String = when (profile.target) {
        CljsRuntimeTarget.BROWSER -> overrideUrl.ifBlank { profile.browserUrl.orEmpty() }
        CljsRuntimeTarget.NODE, CljsRuntimeTarget.BUN, CljsRuntimeTarget.DENO ->
            "http://${overrideHost.ifBlank { profile.inspectHost }}:${overridePort.takeIf { it > 0 } ?: profile.inspectPort ?: 9229}"
    }

    private fun selectedProfile(): CljsBuildProfile? =
        CljsProjectModel.getInstance(project).profiles.firstOrNull { it.id == profileId }

    private fun effectiveProfile(): CljsBuildProfile? = selectedProfile()?.let {
        CljsDebugOverrides(sourceMapRootsOverride, pathMappingsOverride, workingDirectoryOverride, launchCommandOverride).applyTo(it)
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, "cljsProfileId", profileId)
        JDOMExternalizerUtil.writeField(element, "cljsStartBuild", startBuild.toString())
        JDOMExternalizerUtil.writeField(element, "cljsOverrideUrl", overrideUrl)
        JDOMExternalizerUtil.writeField(element, "cljsOverrideHost", overrideHost)
        JDOMExternalizerUtil.writeField(element, "cljsOverridePort", overridePort.toString())
        JDOMExternalizerUtil.writeField(element, "cljsSourceMapRoots", sourceMapRootsOverride)
        JDOMExternalizerUtil.writeField(element, "cljsPathMappings", pathMappingsOverride)
        JDOMExternalizerUtil.writeField(element, "cljsWorkingDirectory", workingDirectoryOverride)
        JDOMExternalizerUtil.writeField(element, "cljsLaunchCommand", launchCommandOverride)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        profileId = JDOMExternalizerUtil.readField(element, "cljsProfileId").orEmpty()
        startBuild = JDOMExternalizerUtil.readField(element, "cljsStartBuild")?.toBooleanStrictOrNull() ?: true
        overrideUrl = JDOMExternalizerUtil.readField(element, "cljsOverrideUrl").orEmpty()
        overrideHost = JDOMExternalizerUtil.readField(element, "cljsOverrideHost").orEmpty()
        overridePort = JDOMExternalizerUtil.readField(element, "cljsOverridePort")?.toIntOrNull() ?: 0
        sourceMapRootsOverride = JDOMExternalizerUtil.readField(element, "cljsSourceMapRoots").orEmpty()
        pathMappingsOverride = JDOMExternalizerUtil.readField(element, "cljsPathMappings").orEmpty()
        workingDirectoryOverride = JDOMExternalizerUtil.readField(element, "cljsWorkingDirectory").orEmpty()
        launchCommandOverride = JDOMExternalizerUtil.readField(element, "cljsLaunchCommand").orEmpty()
    }

}

private class CljsDebugSettingsEditor(private val project: Project) : SettingsEditor<CljsDebugConfiguration>() {
    private val profiles = JComboBox<String>()
    private val startBuild = JCheckBox("Start build/watch process before attaching", true)
    private val url = JTextField()
    private val host = JTextField()
    private val port = JTextField()
    private val sourceRoots = JTextField()
    private val pathMappings = JTextField()
    private val workingDirectory = JTextField()
    private val launchCommand = JTextField()
    private val panel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Build profile:", profiles)
        .addComponent(startBuild)
        .addLabeledComponent("Browser URL override:", url)
        .addLabeledComponent("Node inspect host override:", host)
        .addLabeledComponent("Node inspect port override:", port)
        .addLabeledComponent("Source-map roots override:", sourceRoots)
        .addLabeledComponent("Path mappings (remote=local):", pathMappings)
        .addLabeledComponent("Working directory override:", workingDirectory)
        .addLabeledComponent("Build command override:", launchCommand)
        .panel

    override fun resetEditorFrom(configuration: CljsDebugConfiguration) {
        val model = CljsProjectModel.getInstance(project)
        model.refreshProfiles()
        profiles.removeAllItems()
        model.profiles.filter { it.target == CljsRuntimeTarget.BROWSER }.forEach { profiles.addItem(it.id) }
        profiles.selectedItem = configuration.profileId.ifBlank {
            model.profiles.firstOrNull { it.target == CljsRuntimeTarget.BROWSER }?.id
        }
        startBuild.isSelected = configuration.startBuild
        url.text = configuration.overrideUrl
        host.text = configuration.overrideHost
        port.text = configuration.overridePort.takeIf { it > 0 }?.toString().orEmpty()
        sourceRoots.text = configuration.sourceMapRootsOverride
        pathMappings.text = configuration.pathMappingsOverride
        workingDirectory.text = configuration.workingDirectoryOverride
        launchCommand.text = configuration.launchCommandOverride
    }

    override fun applyEditorTo(configuration: CljsDebugConfiguration) {
        configuration.profileId = profiles.selectedItem as? String ?: ""
        configuration.startBuild = startBuild.isSelected
        configuration.overrideUrl = url.text.trim()
        configuration.overrideHost = host.text.trim()
        configuration.overridePort = port.text.trim().toIntOrNull() ?: 0
        configuration.sourceMapRootsOverride = sourceRoots.text.trim()
        configuration.pathMappingsOverride = pathMappings.text.trim()
        configuration.workingDirectoryOverride = workingDirectory.text.trim()
        configuration.launchCommandOverride = launchCommand.text.trim()
    }

    override fun createEditor(): JComponent = panel
}
