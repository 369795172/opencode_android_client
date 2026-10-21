package ai.opencode.client

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.opencode.client.data.model.CompletionBrief
import ai.opencode.client.notifications.CompletionNotificationSpec
import ai.opencode.client.notifications.CompletionNotifier
import ai.opencode.client.notifications.NotificationPermissionGate
import ai.opencode.client.util.SettingsManager
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Instrumented coverage for the completion notification shell on API 33+.
 * Emulator only (repo rule: never install/run test builds on physical
 * devices).
 *
 * Android kills the app process the moment POST_NOTIFICATIONS is revoked
 * while it runs ("permissions revoked" kill), so the denial state is only
 * reachable with the permission un-granted BEFORE the process starts: a
 * fresh install defaults to denied on API 33+. Tests are therefore ordered
 * [MethodSorters.NAME_ASCENDING] — the denied test consumes the fresh
 * install's un-granted state, and the granted test grants afterwards
 * (granting never kills). Never revoke mid-run.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CompletionNotificationInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    }

    @After
    fun tearDown() {
        notificationManager.activeNotifications
            .filter { it.id in 9100..9355 }
            .forEach { notificationManager.cancel(it.id) }
    }

    @Test
    fun deniedPermissionPostsNothingAndNeverPromptsTwice() {
        assertFalse(
            "precondition: POST_NOTIFICATIONS must be un-granted (fresh install); " +
                "if this fails, run `pm revoke ai.opencode.client android.permission.POST_NOTIFICATIONS` " +
                "from the host while the app process is stopped",
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        )

        // Denied state: the shell refuses to post instead of throwing.
        val notifier = CompletionNotifier(context)
        val spec = specFor("ses_instrumented_denied")
        assertFalse(notifier.notify(spec))
        assertTrue(notificationManager.activeNotifications.none { it.id == spec.notificationId })

        // One-time prompt bookkeeping through the SettingsManager-backed v1 marker.
        val settings = SettingsManager(context)
        settings.notificationPermissionPromptedV1 = false
        assertTrue(
            NotificationPermissionGate.shouldRequestOnce(
                hasPromptedV1 = settings.notificationPermissionPromptedV1,
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                runtimePermissionSupported = true,
            )
        )
        // What MainActivity persists at request time:
        settings.notificationPermissionPromptedV1 = true

        // Relaunch 1 and 2: no repeated prompt, no notification.
        repeat(2) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertFalse(
                        NotificationPermissionGate.shouldRequestOnce(
                            hasPromptedV1 = settings.notificationPermissionPromptedV1,
                            notificationsEnabled = NotificationManagerCompat.from(activity).areNotificationsEnabled(),
                            runtimePermissionSupported = true,
                        )
                    )
                }
            }
        }
        assertTrue(noPermissionDialogVisible())
        assertTrue(notificationManager.activeNotifications.none { it.id == spec.notificationId })
    }

    @Test
    fun grantedPermissionPostsCompletionNotification() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, "android.permission.POST_NOTIFICATIONS"
        )
        assertTrue(waitUntil { NotificationManagerCompat.from(context).areNotificationsEnabled() })

        val notifier = CompletionNotifier(context)
        val spec = specFor("ses_instrumented_grant")
        assertTrue(notifier.notify(spec))

        // The post is applied asynchronously inside NotificationManagerService;
        // poll for the record instead of asserting it synchronously.
        assertTrue(
            waitUntil { notificationManager.activeNotifications.any { it.id == spec.notificationId } }
        )
        val notification = notificationManager.activeNotifications
            .first { it.id == spec.notificationId }.notification
        assertEquals(CompletionNotifier.CHANNEL_ID, notification.channelId)
        // Private body: normalized title + capped brief, no raw paths.
        assertEquals(spec.title, notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(spec.brief, notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertFalse(spec.title.contains("/home/alice"))
        // Public (lock-screen) version: ONLY the normalized title.
        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        assertEquals(spec.title, publicVersion!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertFalse(publicVersion.toString().contains("/home/alice"))
        val publicText = publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertFalse(publicText.contains("report"))

        captureEvidenceScreenshotIfRequested()

        // Cancel and post can race inside NotificationManagerService; poll
        // for the removal instead of asserting it synchronously.
        notifier.cancel(spec.sessionId)
        assertTrue(waitUntil { notificationManager.activeNotifications.none { it.id == spec.notificationId } })
    }

    private fun specFor(sessionId: String): CompletionNotificationSpec {
        val payload = """
            {"id":"$sessionId","messages":[
              {"info":{"role":"user","id":"m0"},"parts":[{"type":"text","text":"go"}]},
              {"info":{"role":"assistant","id":"m1"},
               "parts":[{"type":"text","text":"Done. Report saved at file:///home/user/report.md"}]}
            ]}
        """.trimIndent()
        val brief = CompletionBrief.extract(payload, "Fix login bug in /home/alice/secret-project")
        return CompletionNotificationSpec(sessionId = sessionId, title = brief.title, brief = brief.brief)
    }

    /** Runs a shell command and drains its output so it completes before returning. */
    private fun runShellCommand(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }

    /**
     * With `-e captureEvidence true`, photographs the posted notification
     * (shade expanded) for the task evidence directory. No-op otherwise.
     */
    private fun captureEvidenceScreenshotIfRequested() {
        if (InstrumentationRegistry.getArguments().getString("captureEvidence") != "true") return
        runShellCommand("cmd statusbar expand-notifications")
        Thread.sleep(900)
        runShellCommand("screencap -p /data/local/tmp/task5-notification.png")
        Thread.sleep(600)
        runShellCommand("screencap -p /data/local/tmp/task5-notification-2.png")
        runShellCommand(
            "dumpsys notification --noredact > /data/local/tmp/task5-notification-dumpsys.txt"
        )
        runShellCommand("cmd statusbar collapse")
    }

    /** Binder state (pm grant, cancel) can lag the caller; poll for it. */
    private fun waitUntil(
        timeoutMs: Long = 4_000,
        intervalMs: Long = 100,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
        return condition()
    }

    private fun noPermissionDialogVisible(): Boolean =
        instrumentation.uiAutomation.windows
            .mapNotNull { it.root?.packageName?.toString() }
            .none { pkg -> pkg.contains("permissioncontroller") }
}
