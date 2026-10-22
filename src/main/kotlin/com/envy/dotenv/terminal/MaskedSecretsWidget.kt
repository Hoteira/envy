package com.envy.dotenv.terminal

import com.envy.dotenv.engagement.EngagementTracker
import com.envy.dotenv.licensing.LicenseChecker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent

/**
 * Counts secret occurrences masked in this project's terminals and Run consoles. Masking that
 * works is invisible, and an invisible feature doesn't get renewed: the count goes to the status
 * bar and to the end-of-trial reminder.
 */
@Service(Service.Level.PROJECT)
class MaskCounter(private val project: Project) {
    private val session = AtomicInteger()
    private val refreshQueued = AtomicBoolean(false)

    fun record(count: Int) {
        if (count <= 0) return
        session.addAndGet(count)
        EngagementTracker.getInstance().recordMasked(project, count)
        if (refreshQueued.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater({
                refreshQueued.set(false)
                if (!project.isDisposed) WindowManager.getInstance().getStatusBar(project)?.updateWidget(MaskedSecretsWidget.ID)
            }, ModalityState.any())
        }
    }

    fun sessionCount(): Int = session.get()

    companion object {
        fun getInstance(project: Project): MaskCounter = project.getService(MaskCounter::class.java)

        /** Re-evaluates whether the widget should show (it is Pro-only) after a license change. */
        fun refreshAvailability(project: Project) {
            if (project.isDisposed) return
            try {
                @Suppress("UNCHECKED_CAST")
                val managerClass = Class.forName("com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager") as Class<Any>
                val manager = project.getService(managerClass) ?: return
                managerClass.getMethod("updateWidget", Class::class.java).invoke(manager, MaskedSecretsWidgetFactory::class.java)
            } catch (e: Throwable) {
                Logger.getInstance(MaskCounter::class.java).debug("Couldn't refresh the EnvY status bar widget", e)
            }
        }
    }
}

class MaskedSecretsWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = MaskedSecretsWidget.ID
    override fun getDisplayName(): String = "EnvY Masked Secrets"
    override fun isAvailable(project: Project): Boolean = LicenseChecker.isPaidFeatureAvailableStrict()
    override fun createWidget(project: Project): StatusBarWidget = MaskedSecretsWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class MaskedSecretsWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "EnvY.MaskedSecrets"
    }

    override fun ID(): String = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) {}
    override fun dispose() {}

    override fun getText(): String {
        val count = MaskCounter.getInstance(project).sessionCount()
        return if (count == 0) "EnvY: on" else "EnvY: $count masked"
    }

    override fun getTooltipText(): String {
        val session = MaskCounter.getInstance(project).sessionCount()
        val total = EngagementTracker.getInstance().maskedTotal()
        return "EnvY Pro masked $session secret occurrence(s) in terminal and Run console output this session " +
            "($total since install). Click for EnvY settings."
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        (event.component as? JComponent)?.let { com.envy.dotenv.settings.EnvSettingsPopup.show(project, it) }
    }
}
