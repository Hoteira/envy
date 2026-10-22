package com.envy.dotenv.engagement

import com.envy.dotenv.inspections.SecretLeakInspection
import com.envy.dotenv.licensing.LicenseChecker
import com.envy.dotenv.licensing.ProUpgrade
import com.envy.dotenv.services.EnvFileService
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides when EnvY asks for something: a trial, a review, a purchase. Every ask is an
 * auto-hiding balloon in the "EnvY Suggestions" group (users can mute it in Settings |
 * Notifications), never shown to paying users, and tied to a moment where it is true:
 *
 *  - Day one: the first time a .env with maskable secrets is opened (once ever).
 *  - In the moment: a free user's secret is printed in the terminal or Run console (once per
 *    session, with a permanent "Don't tell me again").
 *  - Later: a periodic Pro reminder after 2 active days, at most 4 times, 10 days apart.
 *  - Review: right after EnvY fixed something (a quick fix, or 20 secrets masked), once ever.
 *  - Trial end: 3 days before a trial ends, with how many secrets EnvY masked during it.
 *
 * At most one ask per IDE session, plus at most one "secret printed" nudge.
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
        var firstRunAskShown: Boolean = false,
        var leakNudgeDisabled: Boolean = false,
        var wins: Int = 0,
        var totalMasked: Long = 0L,
        var trialReminderForEnd: Long = 0L,
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    /** At most one ask per IDE session, across all open projects. */
    private val promptedThisSession = AtomicBoolean(false)
    /** The "secret printed" nudge has its own once-per-session budget. */
    private val nudgedThisSession = AtomicBoolean(false)

    // ---- signals from the rest of the plugin ------------------------------------------------

    /** Cheap and idempotent: called from inspections when they flag a real problem. */
    fun onIssueCaught() {
        if (state.everCaughtIssue) return
        state.everCaughtIssue = true
    }

    /**
     * Called whenever a .env file is shown. Updates the active-day counter (at most one bump per
     * calendar day) and, if nothing has been shown yet this session, evaluates the asks.
     */
    fun onEnvFileShown(project: Project, file: VirtualFile) {
        recordActiveDay()
        if (promptedThisSession.get()) return
        // Off the EDT: counting secrets reads the file index. Notifications can be posted from any thread.
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed) evaluate(project, file)
        }
    }

    /** EnvY just fixed something for the user (a quick fix applied). The best moment to ask for a review. */
    fun onWin(project: Project) {
        state.wins += 1
        if (state.reviewPromptShown) return
        if (state.activeDays < 2 && state.wins < 2) return
        if (!promptedThisSession.compareAndSet(false, true)) return
        state.reviewPromptShown = true
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) showReviewPrompt(project, afterWin = true)
        }
    }

    /** Pro masked [count] secret occurrences. Feeds the status bar, the trial summary and the review ask. */
    fun recordMasked(project: Project, count: Int) {
        val before = synchronized(this) {
            val b = state.totalMasked
            state.totalMasked = b + count
            b
        }
        if (before < MASKED_WIN_THRESHOLD && before + count >= MASKED_WIN_THRESHOLD) onWin(project)
    }

    fun maskedTotal(): Long = state.totalMasked

    /**
     * A free user's secret was just printed in plain text ([where]: "terminal" or "Run console").
     * The only moment the risk is felt, so the only moment this nudge appears.
     */
    fun onSecretPrinted(project: Project, keyName: String?, where: String) {
        if (state.leakNudgeDisabled || state.proPromptDisabled) return
        if (!LicenseChecker.isLicenseDetermined() || LicenseChecker.isPaidFeatureAvailableStrict()) return
        if (!nudgedThisSession.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) showLeakNudge(project, keyName, where)
        }
    }

    // ---- evaluation -------------------------------------------------------------------------

    private fun recordActiveDay() {
        val today = LocalDate.now().toEpochDay()
        if (state.lastActiveEpochDay != today) {
            state.lastActiveEpochDay = today
            state.activeDays += 1
        }
    }

    private fun evaluate(project: Project, file: VirtualFile) {
        if (promptedThisSession.get()) return

        // 1) Trial ending soon: the most time-sensitive ask.
        val trialEnd = LicenseChecker.trialEndsAt()
        if (trialEnd != null && state.trialReminderForEnd != trialEnd) {
            val msLeft = trialEnd - System.currentTimeMillis()
            if (msLeft in 1..TimeUnit.DAYS.toMillis(TRIAL_REMINDER_DAYS)) {
                if (promptedThisSession.compareAndSet(false, true)) {
                    state.trialReminderForEnd = trialEnd
                    showTrialEndingReminder(project, msLeft)
                }
                return
            }
        }

        val unlicensed = LicenseChecker.isLicenseDetermined() && !LicenseChecker.isPaidFeatureAvailableStrict()

        // 2) Day one: the project has secrets that Pro would mask. Once ever.
        if (unlicensed && !state.firstRunAskShown && !state.proPromptDisabled) {
            val secretCount = maskableSecretCount(project)
            if (secretCount > 0 && promptedThisSession.compareAndSet(false, true)) {
                state.firstRunAskShown = true
                state.proPromptLastEpochDay = LocalDate.now().toEpochDay()
                showFirstRunAsk(project, secretCount)
                return
            }
        }

        // 3) Review fallback for people who use EnvY without ever triggering a fix.
        if (!state.reviewPromptShown && state.activeDays >= REVIEW_MIN_DAYS_FALLBACK) {
            if (promptedThisSession.compareAndSet(false, true)) {
                state.reviewPromptShown = true
                showReviewPrompt(project, afterWin = false)
            }
            return
        }

        // 4) Periodic Pro reminder.
        if (unlicensed && shouldShowProPrompt()) {
            if (promptedThisSession.compareAndSet(false, true)) {
                state.proPromptCount += 1
                state.proPromptLastEpochDay = LocalDate.now().toEpochDay()
                showProPrompt(project, fileLikelyContainsSecret(file))
            }
        }
    }

    private fun shouldShowProPrompt(): Boolean {
        if (state.proPromptDisabled) return false
        if (state.activeDays < PRO_MIN_DAYS) return false
        if (state.proPromptCount >= PRO_MAX_PROMPTS) return false
        val last = state.proPromptLastEpochDay
        if (last != 0L && LocalDate.now().toEpochDay() - last < PRO_COOLDOWN_DAYS) return false
        return true
    }

    /** Distinct keys across the project's env files whose values Pro would mask. */
    private fun maskableSecretCount(project: Project): Int = try {
        project.getService(EnvFileService::class.java)
            ?.getAllParsedEntries()
            ?.filter { (key, value) -> SecretLeakInspection.censorTargets(key, value).isNotEmpty() }
            ?.map { it.first }
            ?.distinct()
            ?.size ?: 0
    } catch (e: Exception) {
        if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
        0
    }

    private fun fileLikelyContainsSecret(file: VirtualFile): Boolean {
        return try {
            val text = com.intellij.openapi.application.ReadAction.compute<String?, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(file)?.charsSequence?.toString()
            } ?: return false
            SecretLeakInspection.textContainsSecret(text)
        } catch (e: Exception) {
            false
        }
    }

    // ---- notifications ----------------------------------------------------------------------

    private fun notificationGroup() =
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)

    private fun trialAction(text: String, dialogMessage: String) = object : NotificationAction(text) {
        override fun actionPerformed(e: AnActionEvent, n: Notification) {
            ProUpgrade.requestTrial(dialogMessage)
            n.expire()
        }
    }

    private fun dismissAction(text: String, onDismiss: () -> Unit = {}) = object : NotificationAction(text) {
        override fun actionPerformed(e: AnActionEvent, n: Notification) {
            onDismiss()
            n.expire()
        }
    }

    private fun showFirstRunAsk(project: Project, secretCount: Int) {
        val noun = if (secretCount == 1) "secret" else "secrets"
        notificationGroup().createNotification(
            "EnvY found $secretCount $noun in this project",
            "They're shown in plain text whenever they appear in your terminal, Run console or clipboard. " +
                "EnvY Pro masks them automatically.",
            NotificationType.INFORMATION,
        )
            .addAction(trialAction("Start 30-day free trial", "Try EnvY Pro: secrets masked in your terminal, Run console and clipboard."))
            .addAction(object : NotificationAction("What gets masked?") {
                override fun actionPerformed(e: AnActionEvent, n: Notification) = BrowserUtil.browse(ProUpgrade.PLUGIN_URL)
            })
            .addAction(dismissAction("Not now"))
            .notify(project)
    }

    private fun showLeakNudge(project: Project, keyName: String?, where: String) {
        val what = keyName?.let { "Your $it" } ?: "A secret from your .env file"
        notificationGroup().createNotification(
            "A secret just showed up in your $where",
            "$what was printed in plain text. EnvY Pro masks it automatically, and keeps it off your clipboard.",
            NotificationType.INFORMATION,
        )
            .addAction(trialAction("Try Pro free for 30 days", "Try EnvY Pro: mask secrets in your $where as they're printed."))
            .addAction(dismissAction("Don't tell me again") { state.leakNudgeDisabled = true })
            .notify(project)
    }

    private fun showProPrompt(project: Project, secretPresent: Boolean) {
        val content = if (secretPresent) {
            "This .env file contains secrets. EnvY Pro keeps them out of your terminal, Run console and " +
                "clipboard, and adds SOPS editing. Try it free for 30 days."
        } else {
            "EnvY Pro keeps your .env secrets out of your terminal, Run console and clipboard, and adds " +
                "SOPS editing. Try it free for 30 days."
        }
        notificationGroup().createNotification("EnvY Pro", content, NotificationType.INFORMATION)
            .addAction(trialAction("Start free trial", "Try EnvY Pro free for 30 days."))
            .addAction(dismissAction("Don't show again") { state.proPromptDisabled = true })
            .notify(project)
    }

    private fun showReviewPrompt(project: Project, afterWin: Boolean) {
        val content = if (afterWin) {
            "Glad EnvY could help. A quick review on the Marketplace helps other developers find it."
        } else {
            "Finding EnvY useful? A quick review on the Marketplace helps other developers find it."
        }
        notificationGroup().createNotification("Enjoying EnvY?", content, NotificationType.INFORMATION)
            .addAction(object : NotificationAction("Leave a review") {
                override fun actionPerformed(e: AnActionEvent, n: Notification) {
                    BrowserUtil.browse(REVIEWS_URL)
                    n.expire()
                }
            })
            .addAction(dismissAction("No thanks"))
            .notify(project)
    }

    private fun showTrialEndingReminder(project: Project, msLeft: Long) {
        val days = maxOf(1L, (msLeft + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1))
        val dayWord = if (days == 1L) "day" else "days"
        val masked = state.totalMasked
        val summary = if (masked > 0) {
            "EnvY has masked $masked secret occurrence(s) in your terminal and Run console so far. "
        } else {
            ""
        }
        notificationGroup().createNotification(
            "Your EnvY Pro trial ends in $days $dayWord",
            summary + "After the trial, secrets in your terminal, Run console and clipboard are visible again.",
            NotificationType.INFORMATION,
        )
            .addAction(object : NotificationAction("Keep EnvY Pro") {
                override fun actionPerformed(e: AnActionEvent, n: Notification) {
                    BrowserUtil.browse(ProUpgrade.PRICING_URL)
                    n.expire()
                }
            })
            .addAction(dismissAction("Dismiss"))
            .notify(project)
    }

    companion object {
        private const val GROUP_ID = "EnvY Suggestions"

        private const val REVIEW_MIN_DAYS_FALLBACK = 5
        private const val PRO_MIN_DAYS = 2
        private const val PRO_MAX_PROMPTS = 4
        private const val PRO_COOLDOWN_DAYS = 10
        private const val TRIAL_REMINDER_DAYS = 3L
        private const val MASKED_WIN_THRESHOLD = 20L

        private const val REVIEWS_URL = "https://plugins.jetbrains.com/plugin/31217-envy/reviews"

        fun getInstance(): EngagementTracker =
            ApplicationManager.getApplication().getService(EngagementTracker::class.java)
    }
}
