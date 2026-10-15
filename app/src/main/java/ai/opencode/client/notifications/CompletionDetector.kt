package ai.opencode.client.notifications

import ai.opencode.client.data.model.MessageWithParts
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure eligibility policy for task-completion notifications, mirroring the
 * notification pipeline's reference policy: fixed decision order
 * root check -> idle transition -> busy-start -> busy duration -> ids, with
 * matching snake_case [EligibilityReason.code] strings so eligibility
 * vectors replayed across implementations produce comparable decisions.
 *
 * Pure: `now` is always an explicit parameter; no clock, I/O, or framework
 * dependency. Busy duration is never reconstructed from session timestamps
 * or REST status: only a busy-start observed by this process counts.
 */
enum class EligibilityReason(val code: String) {
    ELIGIBLE("notify"),
    NON_ROOT_SESSION("non_root_session"),
    NO_IDLE_TRANSITION("no_idle_transition"),
    UNKNOWN_BUSY_START("skip"),
    BUSY_BELOW_THRESHOLD("busy_below_threshold"),
    MISSING_IDS("missing_ids"),
}

data class EligibilityDecision(
    val eligible: Boolean,
    val reason: EligibilityReason,
    val sessionId: String?,
    val assistantMessageId: String?,
    /** "<session id>:<assistant message id>"; only set when eligible. */
    val dedupeKey: String?,
    /** Observed busy duration at evaluation time; null when unknown. */
    val busyDurationMs: Long?,
)

data class CompletionEligibilityInput(
    val sessionId: String?,
    val assistantMessageId: String?,
    val isRootSession: Boolean,
    val prevStatus: String?,
    val status: String,
    /** Epoch-ms busy start observed by this process; null = unknown. */
    val busySinceMs: Long?,
    val nowMs: Long,
)

object CompletionPolicy {
    const val DEFAULT_ROOT_SESSIONS_ONLY = true
    const val DEFAULT_BUSY_MS = 120_000L

    fun evaluate(
        input: CompletionEligibilityInput,
        rootSessionsOnly: Boolean = DEFAULT_ROOT_SESSIONS_ONLY,
        busyMs: Long = DEFAULT_BUSY_MS,
    ): EligibilityDecision {
        val sessionId = input.sessionId
        val assistantMessageId = input.assistantMessageId

        fun decision(eligible: Boolean, reason: EligibilityReason): EligibilityDecision =
            EligibilityDecision(
                eligible = eligible,
                reason = reason,
                sessionId = sessionId,
                assistantMessageId = assistantMessageId,
                dedupeKey = if (eligible) "$sessionId:$assistantMessageId" else null,
                busyDurationMs = input.busySinceMs?.let { input.nowMs - it },
            )

        if (rootSessionsOnly && !input.isRootSession) {
            return decision(false, EligibilityReason.NON_ROOT_SESSION)
        }
        if (!(input.prevStatus == "busy" && input.status == "idle")) {
            return decision(false, EligibilityReason.NO_IDLE_TRANSITION)
        }
        val busySince = input.busySinceMs
            ?: return decision(false, EligibilityReason.UNKNOWN_BUSY_START)
        if (input.nowMs - busySince < busyMs) {
            return decision(false, EligibilityReason.BUSY_BELOW_THRESHOLD)
        }
        if (sessionId == null || assistantMessageId == null) {
            return decision(false, EligibilityReason.MISSING_IDS)
        }
        return decision(true, EligibilityReason.ELIGIBLE)
    }
}

/**
 * In-memory tracker fed by every `session.status` SSE event (all sessions,
 * not just the current one). Records the monotonic busy-start per session:
 * the first busy observation of an episode wins and repeats never move it
 * forward. A `retry` status is treated as a continuation of an active busy
 * episode (the session is still working; server retry back-offs must not
 * reset the observed duration). Process death wipes this state, so an idle
 * arriving with no observed busy in this process is a typed skip — the
 * duration is never invented.
 *
 * Receipt state stays local: [recentDecisions] keeps a bounded in-memory
 * ring of typed decisions and never contains brief text.
 */
class CompletionDetector(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class SessionTrack(
        var prevStatus: String? = null,
        var busySinceMs: Long? = null,
    )

    private val tracks = mutableMapOf<String, SessionTrack>()
    private val decisionLog = ArrayDeque<EligibilityDecision>()

    /**
     * Feeds one status event. Returns the decision when the event closed an
     * evaluation (idle transition), otherwise null (busy/retry tracking).
     */
    fun onStatusEvent(sessionId: String, isRootSession: Boolean, statusType: String): EligibilityDecision? {
        pruneStaleTracks()
        val track = tracks.getOrPut(sessionId) { SessionTrack() }
        return when (statusType) {
            "busy" -> {
                if (track.busySinceMs == null) track.busySinceMs = clock()
                track.prevStatus = "busy"
                null
            }
            "retry" -> {
                // Continuation of the active episode; keeps the original
                // busy-start. A retry with no observed busy stays unknown.
                track.prevStatus = if (track.busySinceMs != null) "busy" else "retry"
                null
            }
            "idle" -> {
                val decision = CompletionPolicy.evaluate(
                    CompletionEligibilityInput(
                        sessionId = sessionId,
                        assistantMessageId = null,
                        isRootSession = isRootSession,
                        prevStatus = track.prevStatus,
                        status = "idle",
                        busySinceMs = track.busySinceMs,
                        nowMs = clock(),
                    )
                )
                record(decision)
                track.prevStatus = "idle"
                track.busySinceMs = null
                decision
            }
            else -> {
                track.prevStatus = statusType
                null
            }
        }
    }

    /** Bounded typed-decision receipt log; no brief content. */
    fun recentDecisions(): List<EligibilityDecision> = decisionLog.toList()

    /** Clears all in-memory state (test seam for process recreation). */
    fun reset() {
        tracks.clear()
        decisionLog.clear()
    }

    private fun record(decision: EligibilityDecision) {
        decisionLog.addLast(decision)
        while (decisionLog.size > DECISION_LOG_LIMIT) decisionLog.removeFirst()
    }

    /** Keeps the per-session map bounded: drops idle sessions with no active episode. */
    private fun pruneStaleTracks() {
        if (tracks.size <= TRACK_LIMIT) return
        tracks.entries.removeIf { it.value.prevStatus != "busy" && it.value.busySinceMs == null }
    }

    private companion object {
        const val DECISION_LOG_LIMIT = 50
        const val TRACK_LIMIT = 200
    }
}

/**
 * Builds the CompletionBrief extractor payload from the fetched session
 * window: `{"id": <session id>, "messages": [{id, role, parts: [{type,
 * text}]}]}`. Only ids/roles and part type/text ride along — tool metadata
 * and other part fields are dropped because the extractor only consumes
 * text parts, and the payload never leaves the process. Message order is
 * preserved exactly as the server returned it (the extractor picks the
 * newest qualifying assistant message from the tail).
 */
fun buildBriefPayload(sessionId: String, messages: List<MessageWithParts>): String {
    val payload = buildJsonObject {
        put("id", sessionId)
        put(
            "messages",
            buildJsonArray {
                messages.forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row.info.id)
                            put("role", row.info.role)
                            put(
                                "parts",
                                buildJsonArray {
                                    row.parts.forEach { part ->
                                        add(
                                            buildJsonObject {
                                                put("type", part.type)
                                                put("text", part.text)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
            }
        )
    }
    return payloadJson.encodeToString(payload)
}

private val payloadJson = Json { encodeDefaults = true }
