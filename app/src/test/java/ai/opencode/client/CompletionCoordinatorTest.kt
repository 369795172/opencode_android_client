package ai.opencode.client

import ai.opencode.client.data.model.Message
import ai.opencode.client.data.model.MessageWithParts
import ai.opencode.client.data.model.Part
import ai.opencode.client.data.model.Session
import ai.opencode.client.data.model.SSEEvent
import ai.opencode.client.data.model.SSEPayload
import ai.opencode.client.notifications.CompletionDetector
import ai.opencode.client.notifications.CompletionNotificationCoordinator
import ai.opencode.client.notifications.CompletionNotificationSpec
import ai.opencode.client.notifications.CompletionSeenStore
import ai.opencode.client.notifications.DeliveryOutcome
import ai.opencode.client.notifications.EligibilityReason
import ai.opencode.client.ui.AppState
import ai.opencode.client.ui.handleIncomingSseEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompletionCoordinatorTest {

    private var nowMs = 1_000L
    private var notificationsEnabled = true
    private val handedSpecs = mutableListOf<CompletionNotificationSpec>()
    private val fetchedSessionIds = mutableListOf<String>()
    private val sessions = mutableMapOf<String, Session>()
    private val messagesBySession = mutableMapOf<String, List<MessageWithParts>>()
    private var resumed = true
    private var currentSessionId: String? = null

    private fun assistantRow(id: String, vararg texts: String) = MessageWithParts(
        info = Message(id = id, role = "assistant"),
        parts = texts.map { Part(id = "p_$id", type = "text", text = it) }
    )

    private fun TestScope.coordinatorWith(prefs: FakeSharedPreferences = FakeSharedPreferences()) =
        Pair(prefs, CompletionNotificationCoordinator(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            detector = CompletionDetector(clock = { nowMs }),
            seenStore = CompletionSeenStore(prefs),
            fetchSession = { sessionId ->
                fetchedSessionIds += sessionId
                Result.success(sessions.getValue(sessionId))
            },
            fetchMessages = { sessionId ->
                Result.success(messagesBySession.getValue(sessionId))
            },
            notifyShell = { spec ->
                if (notificationsEnabled) {
                    handedSpecs += spec
                    true
                } else {
                    false
                }
            },
            currentSessionId = { currentSessionId },
            isAppResumed = { resumed },
            now = { nowMs },
        ))

    private fun seedSession(sessionId: String, title: String? = "Fix the thing") {
        sessions[sessionId] = Session(id = sessionId, directory = "/w", title = title)
        messagesBySession[sessionId] = listOf(assistantRow("msg_$sessionId", "All tests pass now"))
    }

    private fun runEligibleTurn(c: CompletionNotificationCoordinator, sessionId: String) {
        c.onSessionStatus(sessionId, isRootSession = true, statusType = "busy")
        nowMs += 120_000L
        c.onSessionStatus(sessionId, isRootSession = true, statusType = "idle")
    }

    @Test
    fun `root busy then idle after 120 seconds hands exactly one notification`() = runTest {
        val (_, coordinator) = coordinatorWith()
        seedSession("ses_happy")

        runEligibleTurn(coordinator, "ses_happy")
        advanceUntilIdle()

        assertEquals(1, handedSpecs.size)
        val spec = handedSpecs.single()
        assertEquals("ses_happy", spec.sessionId)
        assertEquals("Fix the thing", spec.title)
        assertEquals("All tests pass now", spec.brief)
        assertEquals(
            CompletionNotificationSpec.notificationIdFor("ses_happy"),
            spec.notificationId
        )
        assertEquals(listOf(DeliveryOutcome.NOTIFIED), coordinator.recentReceipts().map { it.outcome })
    }

    @Test
    fun `child session completion skips before any fetch`() = runTest {
        val (_, coordinator) = coordinatorWith()
        seedSession("ses_child")

        coordinator.onSessionStatus("ses_child", isRootSession = false, statusType = "busy")
        nowMs += 130_000L
        val decision = coordinator.onSessionStatus("ses_child", isRootSession = false, statusType = "idle")
        advanceUntilIdle()

        assertEquals(EligibilityReason.NON_ROOT_SESSION, decision!!.reason)
        assertTrue(fetchedSessionIds.isEmpty())
        assertTrue(handedSpecs.isEmpty())
    }

    @Test
    fun `fetched session with a parent id is skipped as non root`() = runTest {
        val (_, coordinator) = coordinatorWith()
        // The local list classified it as root, but the fetched session is authoritative.
        sessions["ses_late_child"] = Session(
            id = "ses_late_child", directory = "/w", parentId = "ses_parent", title = "Sub work"
        )
        messagesBySession["ses_late_child"] = listOf(assistantRow("msg_c", "child text"))

        runEligibleTurn(coordinator, "ses_late_child")
        advanceUntilIdle()

        assertTrue(handedSpecs.isEmpty())
        assertEquals(
            listOf(DeliveryOutcome.NON_ROOT_SESSION),
            coordinator.recentReceipts().map { it.outcome }
        )
    }

    @Test
    fun `119 second completion never reaches the fetch`() = runTest {
        val (_, coordinator) = coordinatorWith()
        seedSession("ses_119")

        coordinator.onSessionStatus("ses_119", isRootSession = true, statusType = "busy")
        nowMs += 119_999L
        val decision = coordinator.onSessionStatus("ses_119", isRootSession = true, statusType = "idle")
        advanceUntilIdle()

        assertEquals(EligibilityReason.BUSY_BELOW_THRESHOLD, decision!!.reason)
        assertTrue(fetchedSessionIds.isEmpty())
        assertTrue(handedSpecs.isEmpty())
    }

    @Test
    fun `duplicate idle replay and reconnect replay produce zero extra notifications`() = runTest {
        val (_, coordinator) = coordinatorWith()
        seedSession("ses_dup")

        runEligibleTurn(coordinator, "ses_dup")
        advanceUntilIdle()
        assertEquals(1, handedSpecs.size)

        // Replayed idle events: stage-1 transition guard rejects them.
        repeat(3) {
            nowMs += 1_000L
            val replay = coordinator.onSessionStatus("ses_dup", isRootSession = true, statusType = "idle")
            assertEquals(EligibilityReason.NO_IDLE_TRANSITION, replay!!.reason)
        }
        advanceUntilIdle()
        assertEquals(1, handedSpecs.size)
        assertEquals(1, fetchedSessionIds.size)
    }

    @Test
    fun `same completion after process restart is deduped by the persisted seen-set`() = runTest {
        val (prefs, first) = coordinatorWith(prefs = FakeSharedPreferences())
        seedSession("ses_restart")

        runEligibleTurn(first, "ses_restart")
        advanceUntilIdle()
        assertEquals(1, handedSpecs.size)

        // Process restart: fresh detector/coordinator, same persisted store.
        // A later >=120s busy episode that ends on the same latest assistant
        // message hits the same dedupe key.
        nowMs += 3_600_000L
        val second = coordinatorWith(prefs).second
        runEligibleTurn(second, "ses_restart")
        advanceUntilIdle()

        assertEquals(1, handedSpecs.size)
        assertEquals(
            listOf(DeliveryOutcome.DUPLICATE),
            second.recentReceipts().map { it.outcome }
        )
    }

    @Test
    fun `foreground current session suppresses and marks delivered`() = runTest {
        val (prefs, coordinator) = coordinatorWith()
        seedSession("ses_watch")
        resumed = true
        currentSessionId = "ses_watch"

        runEligibleTurn(coordinator, "ses_watch")
        advanceUntilIdle()

        assertTrue(handedSpecs.isEmpty())
        assertEquals(
            listOf(DeliveryOutcome.SUPPRESSED_FOREGROUND_CURRENT),
            coordinator.recentReceipts().map { it.outcome }
        )
        // Marked delivered: any replay of the same completion stays silent.
        nowMs += 3_600_000L
        val replayCoordinator = coordinatorWith(prefs).second
        runEligibleTurn(replayCoordinator, "ses_watch")
        advanceUntilIdle()
        assertEquals(
            listOf(DeliveryOutcome.DUPLICATE),
            replayCoordinator.recentReceipts().map { it.outcome }
        )
        assertTrue(handedSpecs.isEmpty())
    }

    @Test
    fun `backgrounded app still notifies for the session the user left`() = runTest {
        val (_, coordinator) = coordinatorWith()
        seedSession("ses_bg")
        resumed = false
        currentSessionId = "ses_bg"

        runEligibleTurn(coordinator, "ses_bg")
        advanceUntilIdle()

        assertEquals(1, handedSpecs.size)
    }

    @Test
    fun `resumed app notifies when a different session completes`() = runTest {
        val (_, coordinator) = coordinatorWith()
        seedSession("ses_other")
        seedSession("ses_current")
        resumed = true
        currentSessionId = "ses_current"

        runEligibleTurn(coordinator, "ses_other")
        advanceUntilIdle()

        assertEquals(1, handedSpecs.size)
    }

    @Test
    fun `permission denial records a receipt without crashing or looping`() = runTest {
        val (_, coordinator) = coordinatorWith()
        seedSession("ses_denied")
        notificationsEnabled = false
        resumed = false

        runEligibleTurn(coordinator, "ses_denied")
        advanceUntilIdle()

        assertTrue(handedSpecs.isEmpty()) // notifyShell never accepted anything.
        assertEquals(
            listOf(DeliveryOutcome.NOTIFICATION_DISABLED),
            coordinator.recentReceipts().map { it.outcome }
        )

        // No retry loop: a fresh eligible episode on the same latest message dedupes instead of re-attempting.
        nowMs += 3_600_000L
        runEligibleTurn(coordinator, "ses_denied")
        advanceUntilIdle()
        assertEquals(
            listOf(DeliveryOutcome.NOTIFICATION_DISABLED, DeliveryOutcome.DUPLICATE),
            coordinator.recentReceipts().map { it.outcome }
        )
    }

    @Test
    fun `process recreate before idle yields typed skips and zero notifications`() = runTest {
        // Busy observed by the "dead" process; the recreated detector has no state.
        val (prefs, dead) = coordinatorWith(prefs = FakeSharedPreferences())
        seedSession("ses_pc")
        dead.onSessionStatus("ses_pc", isRootSession = true, statusType = "busy")
        nowMs += 120_000L

        val recreated = coordinatorWith(prefs).second
        val decision = recreated.onSessionStatus("ses_pc", isRootSession = true, statusType = "idle")
        advanceUntilIdle()

        assertEquals(EligibilityReason.NO_IDLE_TRANSITION, decision!!.reason)
        assertTrue(fetchedSessionIds.isEmpty())
        assertTrue(handedSpecs.isEmpty())
    }

    @Test
    fun `fetch failure records a typed receipt and never notifies`() = runTest {
        val prefs = FakeSharedPreferences()
        val coordinator = CompletionNotificationCoordinator(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            detector = CompletionDetector(clock = { nowMs }),
            seenStore = CompletionSeenStore(prefs),
            fetchSession = { Result.failure(IllegalStateException("offline")) },
            fetchMessages = { Result.success(emptyList()) },
            notifyShell = { spec ->
                handedSpecs += spec
                true
            },
            currentSessionId = { null },
            isAppResumed = { false },
            now = { nowMs },
        )
        sessions["ses_offline"] = Session(id = "ses_offline", directory = "/w")

        runEligibleTurn(coordinator, "ses_offline")
        advanceUntilIdle()

        assertTrue(handedSpecs.isEmpty())
        assertEquals(
            listOf(DeliveryOutcome.FETCH_FAILED),
            coordinator.recentReceipts().map { it.outcome }
        )
    }

    @Test
    fun `null title falls back to the openCode task suffix`() = runTest {
        val (_, coordinator) = coordinatorWith()
        seedSession("ses_notitle_abc123", title = null)
        // Given no assistant text: the brief then also falls back to the title.
        messagesBySession["ses_notitle_abc123"] = emptyList()

        runEligibleTurn(coordinator, "ses_notitle_abc123")
        advanceUntilIdle()

        val spec = handedSpecs.single()
        assertEquals("OpenCode task abc123", spec.title)
        assertEquals(spec.title, spec.brief)
    }

    @Test
    fun `null title with assistant text keeps the assistant brief`() = runTest {
        val (_, coordinator) = coordinatorWith()
        seedSession("ses_notitle2_def456", title = null)

        runEligibleTurn(coordinator, "ses_notitle2_def456")
        advanceUntilIdle()

        val spec = handedSpecs.single()
        assertEquals("OpenCode task def456", spec.title)
        assertEquals("All tests pass now", spec.brief)
    }

    @Test
    fun `session status events for non current sessions reach the completion callback`() {
        val state = MutableStateFlow(AppState(currentSessionId = "s1"))
        val seen = mutableListOf<Pair<String, String>>()

        handleIncomingSseEvent(
            state = state,
            event = SSEEvent(
                payload = SSEPayload(
                    type = "session.status",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("s2"))
                        put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
                    }
                )
            ),
            onRefreshMessages = { _, _ -> },
            onRefreshSessions = { },
            onLoadPendingPermissions = { },
            onNonFatalIssue = { },
            onSessionStatusEvent = { sessionId, statusType -> seen += sessionId to statusType },
        )

        assertEquals(listOf("s2" to "busy"), seen)
    }
}
