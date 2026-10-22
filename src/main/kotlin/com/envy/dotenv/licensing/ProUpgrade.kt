package com.envy.dotenv.licensing

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger

/**
 * Every "try Pro" button goes through here. Opens the IDE's own license dialog with EnvY
 * preselected, where a signed-in user starts the 30-day trial (or buys) in two clicks without
 * leaving the IDE. Same mechanism as JetBrains' Marketplace demo plugin (CheckLicense.requestLicense).
 * Falls back to the Marketplace pricing page if the dialog can't be opened.
 */
object ProUpgrade {
    private val LOG = Logger.getInstance(ProUpgrade::class.java)

    const val PRODUCT_CODE = "PENVY"
    const val PRICING_URL = "https://plugins.jetbrains.com/plugin/31217-envy/pricing"
    const val PLUGIN_URL = "https://plugins.jetbrains.com/plugin/31217-envy"

    /** [message] is shown at the top of the license dialog, e.g. why the user is seeing it. */
    fun requestTrial(message: String) {
        ApplicationManager.getApplication().invokeLater({ showRegisterDialog(message) }, ModalityState.nonModal())
    }

    private fun showRegisterDialog(message: String) {
        try {
            val actionManager = ActionManager.getInstance()
            // Open-source IDE builds name it "RegisterPlugins", commercial ones "Register".
            val action = actionManager.getAction("RegisterPlugins") ?: actionManager.getAction("Register")
            if (action == null) {
                BrowserUtil.browse(PRICING_URL)
                return
            }
            val context = DataContext { dataId ->
                when (dataId) {
                    "register.product-descriptor.code" -> PRODUCT_CODE
                    "register.message" -> message
                    else -> null
                }
            }
            val event = AnActionEvent.createEvent(context, Presentation(), "", ActionUiKind.NONE, null)
            ActionUtil.performAction(action, event)
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            LOG.warn("Couldn't open the license dialog, falling back to the pricing page", e)
            BrowserUtil.browse(PRICING_URL)
        }
    }
}
