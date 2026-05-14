package com.cursivejssupport.settings

import com.intellij.openapi.options.Configurable
import com.intellij.util.ui.FormBuilder
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class JsSupportConfigurable : Configurable {

    private val settings = JsSupportSettings.getInstance()
    private val nodeField = JTextField(40)
    private val browserResourceField = JTextField(40)
    private val maxPackagesSpinner = JSpinner(SpinnerNumberModel(400, 1, 5000, 50))
    private val lockfileScan = JCheckBox("Scan lockfiles for transitive npm typings (slow)")

    private var root: JPanel? = null

    override fun getDisplayName(): String = "Cursive JS Support"

    override fun createComponent(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Node.js executable (optional):", nodeField)
            .addLabeledComponent("Bundled browser symbols resource path:", browserResourceField)
            .addLabeledComponent("Max npm packages to index:", maxPackagesSpinner)
            .addComponent(lockfileScan)
            .panel
        root = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return nodeField.text.trim() != s.nodeExecutablePath ||
            browserResourceField.text.trim() != s.browserSymbolsResourcePath ||
            (maxPackagesSpinner.value as Int) != s.maxNpmPackages ||
            lockfileScan.isSelected != s.scanLockfileTransitive
    }

    override fun apply() {
        val s = settings.state
        s.nodeExecutablePath = nodeField.text.trim()
        s.browserSymbolsResourcePath = browserResourceField.text.trim().ifEmpty { "/js/browser-symbols.json.gz" }
        s.maxNpmPackages = maxPackagesSpinner.value as Int
        s.scanLockfileTransitive = lockfileScan.isSelected
    }

    override fun reset() {
        val s = settings.state
        nodeField.text = s.nodeExecutablePath
        browserResourceField.text = s.browserSymbolsResourcePath
        maxPackagesSpinner.value = s.maxNpmPackages
        lockfileScan.isSelected = s.scanLockfileTransitive
    }

    override fun disposeUIResources() {
        root = null
    }
}
