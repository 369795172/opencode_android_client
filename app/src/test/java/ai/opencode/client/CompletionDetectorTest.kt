package ai.opencode.client

import ai.opencode.client.data.model.Message
import ai.opencode.client.data.model.MessageWithParts
import ai.opencode.client.data.model.Part
import ai.opencode.client.notifications.CompletionDetector
import ai.opencode.client.notifications.CompletionEligibilityInput
import ai.opencode.client.notifications.CompletionPolicy
import ai.opencode.client.notifications.EligibilityReason
import ai.opencode.client.notifications.buildBriefPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionDetectorTest {

    private var nowMs = 0L
    private val detector = CompletionDetector(clock = { nowMs })

    private fun input(
        sessionId: String? = "ses_root",
        assistantMessageId: String? = null,
        isRootSession: Boolean = true,
        prevStatus: String? = "busy",
        status: String = "idle",
        busySinceMs: Long? = 0L,
        nowMs: Long = 120_000L,
    ) = CompletionEligibilityInput(
        sessionId = sessionId,
        assistantMessageId = assistantMessageId,
        isRootSession = isRootSession,
        prevStatus = prevStatus,
        status = status,
        busySinceMs = busySinceMs,
        nowMs = nowMs,
    )

    @Test
    fun `evaluate mirrors the reference policy check order root before transition`() {
        // Child session AND a non-idle transition: the root check wins.
        val decision = CompletionPolicy.evaluate(input(isRootSession = false, prevStatus = "idle"))
        assertEquals(EligibilityReason.NON_ROOT_SESSION, decision.reason)
        assertEquals("non_root_session", decision.reason.code)
        assertFalse(decision.eligible)
    }

    @Test
    fun `evaluate reason codes match the reference vocabulary`() {
        assertEquals("notify", EligibilityReason.ELIGIBLE.code)
        assertEquals("non_root_session", EligibilityReason.NON_ROOT_SESSION.code)
        assertEquals("no_idle_transition", EligibilityReason.NO_IDLE_TRANSITION.code)
        assertEquals("skip", EligibilityReason.UNKNOWN_BUSY_START.code)
        assertEquals("busy_below_threshold", EligibilityReason.BUSY_BELOW_THRESHOLD.code)
        assertEquals("missing_ids", EligibilityReason.MISSING_IDS.code)
    }

    @Test
    fun `busy to idle with known start and message id is eligible`() {
        val decision = CompletionPolicy.evaluate(input(assistantMessageId = "msg_1"))
        assertTrue(decision.eligible)
        assertEquals(EligibilityReason.ELIGIBLE, decision.reason)
        assertEquals("ses_root:msg_1", decision.dedupeKey)
        assertEquals(120_000L, decision.busyDurationMs)
    }

    @Test
    fun `threshold boundary 119999 skips and 120000 is eligible`() {
        assertEquals(
            EligibilityReason.BUSY_BELOW_THRESHOLD,
            CompletionPolicy.evaluate(input(nowMs = 119_999L)).reason
        )
        assertEquals(
            EligibilityReason.ELIGIBLE,
            CompletionPolicy.evaluate(input(assistantMessageId = "msg_1", nowMs = 120_000L)).reason
        )
    }

    @Test
    fun `idle without a busy predecessor is no idle transition`() {
        // Covers duplicate/replayed idle and a fresh process seeing only idle.
        assertEquals(
            EligibilityReason.NO_IDLE_TRANSITION,
            CompletionPolicy.evaluate(input(prevStatus = null)).reason
        )
        assertEquals(
            EligibilityReason.NO_IDLE_TRANSITION,
            CompletionPolicy.evaluate(input(prevStatus = "idle")).reason
        )
    }

    @Test
    fun `busy predecessor without a start timestamp is skipped as unknown start`() {
        // Busy was observed (e.g. before a restart/reconnect) but this process
        // has no timestamp for it: never invent a duration.
        val decision = CompletionPolicy.evaluate(input(busySinceMs = null))
        assertEquals(EligibilityReason.UNKNOWN_BUSY_START, decision.reason)
        assertEquals("skip", decision.reason.code)
        assertNull(decision.busyDurationMs)
    }

    @Test
    fun `missing assistant message id is reported after all other gates pass`() {
        assertEquals(
            EligibilityReason.MISSING_IDS,
            CompletionPolicy.evaluate(input(assistantMessageId = null)).reason
        )
    }

    @Test
    fun `tracker fires for root sessions other than the current one`() {
        nowMs = 1_000L
        assertNull(detector.onStatusEvent("ses_other", isRootSession = true, statusType = "busy"))
        nowMs = 121_000L
        val decision = detector.onStatusEvent("ses_other", isRootSession = true, statusType = "idle")
        assertEquals(EligibilityReason.MISSING_IDS, decision!!.reason)
        assertEquals("ses_other", decision.sessionId)
        assertTrue(decision.busyDurationMs!! >= 120_000L)
    }

    @Test
    fun `tracker excludes child sessions`() {
        nowMs = 1_000L
        detector.onStatusEvent("ses_child", isRootSession = false, statusType = "busy")
        nowMs = 130_000L
        val decision = detector.onStatusEvent("ses_child", isRootSession = false, statusType = "idle")
        assertEquals(EligibilityReason.NON_ROOT_SESSION, decision!!.reason)
    }

    @Test
    fun `tracker skips below-threshold completions`() {
        nowMs = 1_000L
        detector.onStatusEvent("ses_short", isRootSession = true, statusType = "busy")
        nowMs = 120_998L // 119_998ms busy
        val decision = detector.onStatusEvent("ses_short", isRootSession = true, statusType = "idle")
        assertEquals(EligibilityReason.BUSY_BELOW_THRESHOLD, decision!!.reason)
    }

    @Test
    fun `duplicate idle without a new busy episode never re-qualifies`() {
        nowMs = 1_000L
        detector.onStatusEvent("ses_dup", isRootSession = true, statusType = "busy")
        nowMs = 121_000L
        detector.onStatusEvent("ses_dup", isRootSession = true, statusType = "idle")
        nowMs = 122_000L
        val replay = detector.onStatusEvent("ses_dup", isRootSession = true, statusType = "idle")
        assertEquals(EligibilityReason.NO_IDLE_TRANSITION, replay!!.reason)

        // A new busy episode under the threshold also never re-qualifies.
        nowMs = 130_000L
        detector.onStatusEvent("ses_dup", isRootSession = true, statusType = "busy")
        nowMs = 140_000L
        val short = detector.onStatusEvent("ses_dup", isRootSession = true, statusType = "idle")
        assertEquals(EligibilityReason.BUSY_BELOW_THRESHOLD, short!!.reason)
    }

    @Test
    fun `retry continues the busy episode without resetting the start`() {
        nowMs = 1_000L
        detector.onStatusEvent("ses_retry", isRootSession = true, statusType = "busy")
        nowMs = 60_000L
        assertNull(detector.onStatusEvent("ses_retry", isRootSession = true, statusType = "retry"))
        nowMs = 121_000L
        val decision = detector.onStatusEvent("ses_retry", isRootSession = true, statusType = "idle")
        // 120s measured from the original busy start, not the retry.
        assertEquals(EligibilityReason.MISSING_IDS, decision!!.reason)
        assertEquals(120_000L, decision.busyDurationMs)
    }

    @Test
    fun `repeated busy events keep the monotonic earliest start`() {
        nowMs = 1_000L
        detector.onStatusEvent("ses_mono", isRootSession = true, statusType = "busy")
        nowMs = 60_000L
        detector.onStatusEvent("ses_mono", isRootSession = true, statusType = "busy")
        nowMs = 121_000L
        val decision = detector.onStatusEvent("ses_mono", isRootSession = true, statusType = "idle")
        assertEquals(120_000L, decision!!.busyDurationMs)
    }

    @Test
    fun `process-recreated empty busy state yields a typed skip`() {
        val recreated = CompletionDetector(clock = { 500_000L })
        val decision = recreated.onStatusEvent("ses_gone", isRootSession = true, statusType = "idle")
        assertEquals(EligibilityReason.NO_IDLE_TRANSITION, decision!!.reason)
        assertTrue(recreated.recentDecisions().single().reason === EligibilityReason.NO_IDLE_TRANSITION)
    }

    @Test
    fun `decision log is bounded and holds typed receipts only`() {
        repeat(60) { index ->
            nowMs = index.toLong()
            detector.onStatusEvent("ses_$index", isRootSession = false, statusType = "idle")
        }
        assertEquals(50, detector.recentDecisions().size)
        detector.reset()
        assertTrue(detector.recentDecisions().isEmpty())
    }

    @Test
    fun `buildBriefPayload carries session id plus flat message rows for the extractor`() {
        val messages = listOf(
            MessageWithParts(
                info = Message(id = "msg_user", role = "user"),
                parts = listOf(Part(id = "p1", type = "text", text = "please fix"))
            ),
            MessageWithParts(
                info = Message(id = "msg_old", role = "assistant"),
                parts = listOf(Part(id = "p2", type = "text", text = "older answer"))
            ),
            MessageWithParts(
                info = Message(id = "msg_new", role = "assistant"),
                parts = listOf(
                    Part(id = "p3", type = "text", text = "done"),
                    Part(id = "p4", type = "tool")
                )
            ),
        )
        val payload = Json.parseToJsonElement(
            buildBriefPayload("ses_payload", messages)
        ).jsonObject

        assertEquals("ses_payload", payload["id"]!!.jsonPrimitive.content)
        val rows = payload["messages"]!!.jsonArray
        assertEquals(3, rows.size)
        assertEquals("msg_new", rows.last().jsonObject["id"]!!.jsonPrimitive.content)

        val brief = ai.opencode.client.data.model.CompletionBrief.extract(
            buildBriefPayload("ses_payload", messages),
            "Session title",
        )
        assertEquals("msg_new", brief.assistantMessageId)
        assertEquals("done", brief.brief)
        assertEquals("ses_payload:msg_new", brief.dedupeKey)
    }
}
