package com.cursivejssupport.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "JsSupportSettings", storages = [Storage("cursive-js-support.xml")])
class JsSupportSettings : PersistentStateComponent<JsSupportSettings.State> {

    data class State(
        var nodeExecutablePath: String = "",
        var browserSymbolsResourcePath: String = "/js/browser-symbols.json.gz",
        var maxNpmPackages: Int = 400,
        var scanLockfileTransitive: Boolean = false,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        @JvmStatic
        fun getInstance(): JsSupportSettings = service()
    }
}
