package ai.opencode.client

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import ai.opencode.client.notifications.CompletionNotificationSpec
import ai.opencode.client.notifications.CompletionNotifier
import ai.opencode.client.tts.TtsController
import ai.opencode.client.tts.TtsService
import ai.opencode.client.util.OpenCodeDeepLinkParser
import ai.opencode.client.util.OpenCodeDeepLinkParseResult
import dagger.hilt.android.EntryPointAccessors
import java.io.FileInputStream
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * Instrumented coverage for the completion notification's deep-link tap and
 * "Play brief" action on API 33+. Emulator only (repo rule: never install/run
 * test builds on physical devices). Requires POST_NOTIFICATIONS granted
 * (granting never kills; only revocation does — never revoke mid-run).
 *
 * The deep-link happy path runs against a MockWebServer bound on the device,
 * so session validation proves "exact current-host session" without a real
 * OpenCode server. The Play test measures the FGS question through a REAL
 * notification-shade tap (input tap → SystemUI dispatch, which carries the
 * user-interaction foreground-service allowance; an in-process
 * PendingIntent.send() would not be equivalent).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PostCompletionActionInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    private val targetSessionId = "ses_target_1"
    private val targetMessageId = "msg_target_1"
    private val targetTitle = "DeepLink Target Session"
    private val fixtureBrief = "All checks green. Brief completed."
    private val staleMessageId = "msg_STALE_001"
    private val staleText = "STALE payload must never be spoken"

    private var server: MockWebServer? = null

    /** All mock-server request paths seen so far; drained poll-safe. */
    private val requestLog = mutableListOf<String>()

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        assertTrue(waitUntil { NotificationManagerCompat.from(context).areNotificationsEnabled() })
        runShellCommand("logcat -c")
    }

    @After
    fun tearDown() {
        runShellCommand("cmd statusbar collapse")
        notificationManager.activeNotifications
            .filter { it.id in 9100..9355 }
            .forEach { notificationManager.cancel(it.id) }
        server?.close()
    }

    @Test
    fun aActionSurfaceIsPlayOnlyWithNonExportedMediaService() {
        val notifier = CompletionNotifier(context)
        val spec = specFor(targetSessionId)
        assertTrue(notifier.notify(spec))
        assertTrue(waitUntil { notificationManager.activeNotifications.any { it.id == spec.notificationId } })
        val notification = notificationManager.activeNotifications
            .first { it.id == spec.notificationId }.notification

        // Exactly one action: local playback. Never prompt/tool/approval.
        assertEquals(1, notification.actions.size.toLong())
        assertEquals(CompletionNotifier.PLAY_ACTION_LABEL, notification.actions[0].title)
        assertNotNull(notification.contentIntent)

        // TtsService stays non-exported with the mediaPlayback FGS type.
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, TtsService::class.java),
            PackageManager.GET_META_DATA
        )
        assertFalse(serviceInfo.exported)
        assertTrue(
            (serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0
        )

        // Immutability of every shell PendingIntent is the compile-time
        // constant asserted in unit tests; both action families share it.
        assertEquals(
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            CompletionNotifier.PENDING_INTENT_FLAGS
        )
        notifier.cancel(spec.sessionId)
    }

    @Test
    fun bContentTapOpensExactCurrentHostSession() {
        startMockServer()
        launchConnectedActivity()

        val notifier = CompletionNotifier(context)
        val spec = specFor(targetSessionId)
        assertTrue(notifier.notify(spec))
        assertTrue(waitUntil { notificationManager.activeNotifications.any { it.id == spec.notificationId } })

        if (captureEvidence()) {
            runShellCommand("cmd statusbar expand-notifications")
            Thread.sleep(1200)
            runShellCommand("screencap -p /data/local/tmp/task10-shade.png")
            runShellCommand("cmd statusbar collapse")
        }

        // Tap: dispatch the notification's content PendingIntent exactly as
        // the system would on a body tap.
        val notification = notificationManager.activeNotifications
            .first { it.id == spec.notificationId }.notification
        notification.contentIntent.send()

        // The deep-link pipeline ran end-to-end on the CURRENT host: the app
        // validated the session through the configured (mock) server.
        assertTrue(
            "expected session validation request GET /session/$targetSessionId",
            waitUntil(timeoutMs = 15_000) { pollRequests().any { it.startsWith("/session/$targetSessionId") } }
        )
        assertTrue(
            "expected message window request after navigation",
            waitUntil { pollRequests().any { it.startsWith("/session/$targetSessionId/message") } }
        )

        if (captureEvidence()) {
            Thread.sleep(1200)
            runShellCommand("screencap -p /data/local/tmp/task10-opened-session.png")
        }
        notifier.cancel(spec.sessionId)
    }

    @Test
    fun cMalformedSessionIdNeverNavigates() {
        startMockServer()
        launchConnectedActivity()
        val pathsBefore = pollRequests().size

        // Defense in depth: the parser rejects non-ses_ links outright.
        assertTrue(
            OpenCodeDeepLinkParser.parse("opencode://session/not_valid_id") is
                OpenCodeDeepLinkParseResult.InvalidSessionLink
        )

        val notifier = CompletionNotifier(context)
        val spec = specFor("not_valid_id")
        assertTrue(notifier.notify(spec))
        assertTrue(waitUntil { notificationManager.activeNotifications.any { it.id == spec.notificationId } })

        notificationManager.activeNotifications
            .first { it.id == spec.notificationId }.notification
            .contentIntent.send()
        Thread.sleep(1_500)

        // The builder attached the generic launch intent (no VIEW data), so
        // the app opened but no session lookup for the malformed id occurred.
        assertTrue(pollRequests().drop(pathsBefore).none { it.contains("not_valid_id") })
        notifier.cancel(spec.sessionId)
    }

    @Test
    fun dPlayActionSpeaksExactBriefOverridingStalePayload() {
        seedStagedSpeakPayload()

        val notifier = CompletionNotifier(context)
        val spec = specFor(targetSessionId)
        assertTrue(notifier.notify(spec))
        assertTrue(waitUntil { notificationManager.activeNotifications.any { it.id == spec.notificationId } })

        // Background the app, then tap "Play brief" through the real shade:
        // SystemUI dispatches the action with user-interaction semantics, the
        // honest measurement for background foreground-service starts.
        uiDevice.pressHome()
        Thread.sleep(800)
        assertTrue(
            "could not locate '${CompletionNotifier.PLAY_ACTION_LABEL}' in the shade",
            tapNotificationAction(CompletionNotifier.PLAY_ACTION_LABEL, spec.title)
        )

        assertTrue(
            "expected speak request source=notification len=${fixtureBrief.length} messageId=$targetMessageId",
            waitUntil(timeoutMs = 20_000) { logcatTts().contains(speakLineFor(fixtureBrief, targetMessageId)) }
        )
        val ttsLog = logcatTts()
        assertFalse(
            "stale staged payload must never be spoken; log:\n$ttsLog",
            ttsLog.contains(speakLineFor(staleText, staleMessageId)) ||
                ttsLog.contains("messageId=$staleMessageId")
        )
        assertFalse(
            "background FGS start from a real notification tap must not throw " +
                "ForegroundServiceStartNotAllowedException; log:\n$ttsLog",
            ttsLog.contains("ForegroundServiceStartNotAllowedException")
        )

        if (captureEvidence()) {
            runShellCommand("screencap -p /data/local/tmp/task10-play-tts.png")
        }
        stopTts()
    }

    // --- helpers -----------------------------------------------------------

    private fun specFor(sessionId: String): CompletionNotificationSpec =
        CompletionNotificationSpec(
            sessionId = sessionId,
            title = if (sessionId == targetSessionId) targetTitle else "Malformed completion",
            brief = fixtureBrief,
            assistantMessageId = if (sessionId == targetSessionId) targetMessageId else null,
        )

    private fun speakLineFor(text: String, messageId: String): String =
        "speak request source=notification len=${text.length} messageId=$messageId"

    private fun startMockServer() {
        if (server != null) return
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/global/health") -> json("""{"healthy":true,"version":"mock"}""")
                    path.startsWith("/session/$targetSessionId/message") -> json("[]")
                    path.startsWith("/session/$targetSessionId") ->
                        json("""{"id":"$targetSessionId","directory":"/demo","title":"$targetTitle"}""")
                    path.startsWith("/session") -> json(
                        """[{"id":"$targetSessionId","directory":"/demo","title":"$targetTitle"}]"""
                    )
                    path.startsWith("/agent") -> json("[]")
                    else -> json("{}")
                }
            }

            private fun json(body: String) = MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        }
        mock.start()
        server = mock
    }

    private fun launchConnectedActivity() {
        val port = server!!.port
        val launch = Intent(context, MainActivity::class.java).apply {
            putExtra("test_server_url", "http://127.0.0.1:$port")
            putExtra("test_profile_name", "MockQA")
        }
        ActivityScenario.launch<MainActivity>(launch)
        assertTrue(
            "expected the app to health-check the mock server",
            waitUntil(timeoutMs = 20_000) { pollRequests().any { it.startsWith("/global/health") } }
        )
    }

    private fun pollRequests(): List<String> {
        while (true) {
            val request = server?.takeRequest(0, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
            requestLog += request.path.orEmpty()
        }
        return requestLog.toList()
    }

    /** Stages a stale TtsController payload without dispatching anything. */
    private fun seedStagedSpeakPayload() {
        val controller = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ai.opencode.client.di.TtsServiceEntryPoint::class.java
        ).ttsController()
        val payloadClass = Class.forName("ai.opencode.client.tts.TtsSpeakPayload")
        val payload = payloadClass.getConstructor(String::class.java, String::class.java, Float::class.java)
            .newInstance(staleText, staleMessageId, 1.0f)
        val field = TtsController::class.java.getDeclaredField("pendingSpeakPayload")
        field.isAccessible = true
        field.set(controller, payload)
    }

    private fun stopTts() {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                ai.opencode.client.di.TtsServiceEntryPoint::class.java
            ).ttsController().stop()
        }
    }

    private val uiDevice: UiDevice by lazy { UiDevice.getInstance(instrumentation) }

    /**
     * Opens the shade and taps the notification action labeled [label] via
     * the instrumentation's own UiAutomation connection (the shell
     * `uiautomator dump` binary conflicts with it). When actions are hidden
     * behind a collapsed row, the row is expanded first. Returns false when
     * the action can't be located.
     */
    private fun tapNotificationAction(label: String, rowTitle: String): Boolean {
        if (!uiDevice.openNotification()) return false
        Thread.sleep(1_200)
        var action = uiDevice.wait(Until.findObject(By.text(label)), 3_000)
            ?: uiDevice.wait(Until.findObject(By.desc(label)), 3_000)
        if (action == null) {
            uiDevice.wait(Until.findObject(By.text(rowTitle)), 2_000)?.click()
            Thread.sleep(900)
            action = uiDevice.wait(Until.findObject(By.text(label)), 3_000)
                ?: uiDevice.wait(Until.findObject(By.desc(label)), 3_000)
        }
        if (action == null) {
            uiDevice.pressBack()
            return false
        }
        action.click()
        Thread.sleep(400)
        uiDevice.pressBack()
        return true
    }

    private fun logcatTts(): String =
        shellOutput("logcat -d -s TtsService:V AndroidRuntime:E") ?: ""

    private fun captureEvidence(): Boolean =
        InstrumentationRegistry.getArguments().getString("captureEvidence") == "true"

    /** Runs a shell command and drains its output so it completes before returning. */
    private fun runShellCommand(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun shellOutput(command: String): String? = runCatching {
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }.getOrNull()

    /** Binder state (pm grant, post, service start) can lag; poll for it. */
    private fun waitUntil(
        timeoutMs: Long = 8_000,
        intervalMs: Long = 150,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
        return condition()
    }
}
