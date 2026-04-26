package com.envy.dotenv.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "EnvySettings", storages = [Storage("envy.xml")])
class EnvySettings : PersistentStateComponent<EnvySettings.State> {

    data class State(
        var syntaxHighlighting: Boolean = true,
        var duplicateKeyDetection: Boolean = true,
        var presentationMode: Boolean = true,
        var envVarAutocomplete: Boolean = true,
        var terminalSecretCensor: Boolean = true,
        var consoleSecretRedaction: Boolean = true,
        var secretLeakDetection: Boolean = true,
        var gitignoreVerification: Boolean = true,
        var inlineGhostCompletion: Boolean = true,
        var sopsIntegration: Boolean = true
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): EnvySettings =
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(EnvySettings::class.java)
    }
}
