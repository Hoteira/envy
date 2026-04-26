package com.envy.dotenv.terminal

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType

class ToggleTerminalSecretsAction : AnAction("Toggle Terminal Secrets Visibility", "Reveals or hides censored secrets in the terminal", null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val censor = project.getService(TerminalSecretCensor::class.java) ?: return
        val count = censor.toggleAll()
        Notification("EnvY", "Toggled secrets in $count terminal(s).", NotificationType.INFORMATION)
            .notify(project)
    }
}
