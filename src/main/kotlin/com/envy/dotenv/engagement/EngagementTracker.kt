package com.envy.dotenv.engagement

import com.envy.dotenv.inspections.SecretLeakInspection
import com.envy.dotenv.licensing.LicenseChecker
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides when to surface the (one-time) "leave a review" ask and the later, capped EnvY Pro
 * upgrade ask. Both are delivered as auto-hiding balloon notifications so they never block the
 * editor or clutter the gutter, and at most one is ever shown per IDE session.
 *
 * Design rules that keep this from annoying a developer audience:
 *  - Prompts fire on genuine engagement signals, never on a timer or at startup.
 *  - The review ask is shown at most once, ever.
 *  - The Pro ask is never shown to a paying user, is gated behind sustained use, is capped, has a
 *    cooldown, and offers a permanent "Don't show again".
 *  - The whole group can be muted by the user in Settings | Notifications.
 */
@Service(Service.Level.APP)
@State(name = "EnvyEngagement", storages = [Storage("envy.xml")])
class EngagementTracker : PersistentStateComponent<EngagementTracker.State> {

    data class State(
        var activeDays: Int = 0,
        var lastActiveEpochDay: Long = 0L,
        var everCaughtIssue: Boolean = false,
        var reviewPromptShown: Boolean = false,
        var proPromptCount: Int = 0,
        var proPromptLastEpochDay: Long = 0L,
        var proPromptDisabled: Boolean = false,
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    /** At most one engagement balloon per IDE session, across all open projects. */
    private val promptedThisSession = AtomicBoolean(false)

    /** Cheap and idempotent: called from inspections when they flag a real problem. */
    fun onIssueCaught() {
        if (state.everCaughtIssue) return
        state.everCaughtIssue = true
    }

    /**
     * Called whenever a .env file is shown. Updates the active-day counter (at most one bump per
     * calendar day) and, if nothing has been shown yet this session, evaluates the prompts off the
     * caller's thread.
     */
    fun onEnvFileShown(project: Project, file: VirtualFile) {
        recordActiveDay()
        if (promptedThisSession.get()) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) evaluate(project, file)
        }
    }

    private fun recordActiveDay() {
        val today = LocalDate.now().toEpochDay()
        if (state.lastActiveEpochDay != today) {
            state.lastActiveEpochDay = today
            state.activeDays += 1
        }
    }

    private fun evaluate(project: Project, file: VirtualFile) {
        if (promptedThisSession.get()) return

        // 1) Review ask — earliest, but only once the plugin has demonstrably helped.
        val earnedByWin = state.everCaughtIssue && state.activeDays >= REVIEW_MIN_DAYS_WITH_WIN
        val earnedByUse = state.activeDays >= REVIEW_MIN_DAYS_FALLBACK
        if (!state.reviewPromptShown && (earnedByWin || earnedByUse)) {
            if (promptedThisSession.compareAndSet(false, true)) {
                state.reviewPromptShown = true
                showReviewPrompt(project)
            }
            return
        }

        // 2) Pro ask — strictly later, never to paying users.
        if (shouldShowProPrompt()) {
            if (promptedThisSession.compareAndSet(false, true)) {
                state.proPromptCount += 1
                state.proPromptLastEpochDay = LocalDate.now().toEpochDay()
                showProPrompt(project, fileLikelyContainsSecret(file))
            }
        }
    }

    private fun shouldShowProPrompt(): Boolean {
        if (state.proPromptDisabled) return false
        if (!state.reviewPromptShown) return false               // Pro comes after the review ask.
        if (state.activeDays < PRO_MIN_DAYS) return false
        if (state.proPromptCount >= PRO_MAX_PROMPTS) return false
        // Never bug a paying user; stay silent until the license state is actually resolved.
        if (!LicenseChecker.isLicenseDetermined()) return false
        if (LicenseChecker.isPaidFeatureAvailableStrict()) return false
        val last = state.proPromptLastEpochDay
        if (last != 0L && LocalDate.now().toEpochDay() - last < PRO_COOLDOWN_DAYS) return false
        return true
    }

    private fun fileLikelyContainsSecret(file: VirtualFile): Boolean {
        return try {
            val doc = FileDocumentManager.getInstance().getDocument(file) ?: return false
            SecretLeakInspection.textContainsSecret(doc.charsSequence.toString())
        } catch (e: Exception) {
            false
        }
    }

    private fun notificationGroup() =
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)

    private fun showReviewPrompt(project: Project) {
        val notification = notificationGroup().createNotification(
            "Enjoying EnvY?",
            "EnvY just helped you with a .env file. A quick review on the Marketplace helps other developers find it.",
            NotificationType.INFORMATION,
        )
        notification.addAction(object : NotificationAction("Leave a review") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                BrowserUtil.browse(REVIEWS_URL)
                n.expire()
            }
        })
        notification.addAction(object : NotificationAction("No thanks") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) = n.expire()
        })
        notification.notify(project)
    }

    private fun showProPrompt(project: Project, secretPresent: Boolean) {
        val content = if (secretPresent) {
            "EnvY found a possible secret in this .env file. EnvY Pro keeps secrets out of your " +
                "terminal, console, and clipboard, and flags secrets that aren't gitignored. " +
                "Try it free for 30 days."
        } else {
            "You've been getting a lot out of EnvY. Pro keeps secrets out of your terminal, " +
                "console, and clipboard, and adds SOPS support. Try it free for 30 days."
        }
        val notification = notificationGroup().createNotification(
            "EnvY Pro",
            content,
            NotificationType.INFORMATION,
        )
        notification.addAction(object : NotificationAction("Start free trial") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                BrowserUtil.browse(PRICING_URL)
                n.expire()
            }
        })
        notification.addAction(object : NotificationAction("Don't show again") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                state.proPromptDisabled = true
                n.expire()
            }
        })
        notification.notify(project)
    }

    companion object {
        private const val GROUP_ID = "EnvY Suggestions"

        private const val REVIEW_MIN_DAYS_WITH_WIN = 2
        private const val REVIEW_MIN_DAYS_FALLBACK = 4
        private const val PRO_MIN_DAYS = 6
        private const val PRO_MAX_PROMPTS = 2
        private const val PRO_COOLDOWN_DAYS = 21

        private const val REVIEWS_URL = "https://plugins.jetbrains.com/plugin/31217-envy/reviews"
        private const val PRICING_URL = "https://plugins.jetbrains.com/plugin/31217-envy/pricing"

        fun getInstance(): EngagementTracker =
            ApplicationManager.getApplication().getService(EngagementTracker::class.java)
    }
}
