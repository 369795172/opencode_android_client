package ai.opencode.client.notifications

import ai.opencode.client.data.model.CompletionBrief
import ai.opencode.client.data.model.MessageWithParts
import ai.opencode.client.data.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Delivery outcome after eligibility passed. Receipt state is local and
 * typed: no brief, title, or session text is ever recorded or persisted.
 */
enum class DeliveryOutcome {
    /** Handed to the notification shell and the shell accepted it. */
    NOTIFIED,

    /** Shell returned false (notifications disabled / permission denied); never retried. */
    NOTIFICATION_DISABLED,

    /** Dedupe key already in the persisted seen-set (replay/reconnect/process restart). */
    DUPLICATE,

    /** App resumed with the completed session on screen; marked delivered instead of posted. */
    SUPPRESSED_FOREGROUND_CURRENT,

    /** Session/message fetch failed; best-effort channel, no retry loop. */
    FETCH_FAILED,

    /** Fetched session turned out to be a child (subagent) session. */
    NON_ROOT_SESSION,
}

data class DeliveryReceipt(
    val sessionId: String,
    val dedupeKey: String?,
    val outcome: DeliveryOutcome,
    val atMs: Long,
)

/**
 * Connects the pure [CompletionDetector] to the app: every `session.status`
 * event for every session feeds the detector; only an eligible idle (root
 * session, observed busy >= 120s) triggers the session + message fetch that
 * builds a [CompletionBrief]. The prepared notification then flows to the
 * todo-5 shell ([CompletionNotifier.notify]) via [notifyShell] — never
 * around it. Foreground suppression (app resumed with the completed session
 * visible) marks the key delivered instead of posting. Permission denial
 * (shell returns false) records [DeliveryOutcome.NOTIFICATION_DISABLED] and
 * never crashes or retries.
 *
 * All collaborators are injected, so the whole decision chain is
 * data-testable on the JVM with a fake clock.
 */
class CompletionNotificationCoordinator(
    scope: CoroutineScope,
    private val detector: CompletionDetector,
    private val seenStore: CompletionSeenStore,
    private val fetchSession: suspend (String) -> Result<Session>,
    private val fetchMessages: suspend (String) -> Result<List<MessageWithParts>>,
    private val notifyShell: (CompletionNotificationSpec) -> Boolean,
    private val currentSessionId: () -> String?,
    private val isAppResumed: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val onIssue: (String) -> Unit = {},
) {
    private val deliveryJob = SupervisorJob(scope.coroutineContext[Job])
    private val deliveryScope = CoroutineScope(scope.coroutineContext + deliveryJob)
    private val inFlight = mutableSetOf<String>()
    private val receipts = ArrayDeque<DeliveryReceipt>()

    /**
     * Feeds one `session.status` event (any session). Returns the stage-1
     * [EligibilityDecision] synchronously; delivery runs asynchronously.
     */
    fun onSessionStatus(sessionId: String, isRootSession: Boolean, statusType: String): EligibilityDecision? {
        val decision = detector.onStatusEvent(sessionId, isRootSession, statusType)
        // MISSING_IDS with a session id means every stage-1 gate passed and
        // only the assistant message id is pending the post-eligibility fetch.
        val eligibleForDelivery = decision?.sessionId?.takeIf {
            decision.reason == EligibilityReason.ELIGIBLE ||
                decision.reason == EligibilityReason.MISSING_IDS
        }
        if (eligibleForDelivery != null && inFlight.add(eligibleForDelivery)) {
            deliveryScope.launch {
                try {
                    deliver(eligibleForDelivery)
                } finally {
                    inFlight.remove(eligibleForDelivery)
                }
            }
        }
        return decision
    }

    /** Bounded local receipt log (typed outcomes only, no briefs). */
    fun recentReceipts(): List<DeliveryReceipt> = receipts.toList()

    /** Cancels in-flight delivery work (host teardown). */
    fun cancel() {
        deliveryJob.cancel()
    }

    private suspend fun deliver(sessionId: String) {
        val session = fetchSession(sessionId).getOrNull()
        if (session == null) {
            record(sessionId, null, DeliveryOutcome.FETCH_FAILED)
            return
        }
        // The fetched session is authoritative: a stale local list may have
        // mis-classified a child as root, but never the other way around.
        if (session.parentId != null) {
            record(sessionId, null, DeliveryOutcome.NON_ROOT_SESSION)
            return
        }
        val messages = fetchMessages(sessionId).getOrNull()
        if (messages == null) {
            record(sessionId, null, DeliveryOutcome.FETCH_FAILED)
            return
        }
        val brief = CompletionBrief.extract(
            sessionPayload = buildBriefPayload(sessionId, messages),
            title = session.title,
        )
        val dedupeKey = brief.dedupeKey
        if (dedupeKey == null) {
            record(sessionId, null, DeliveryOutcome.FETCH_FAILED)
            return
        }
        if (seenStore.contains(dedupeKey)) {
            record(sessionId, dedupeKey, DeliveryOutcome.DUPLICATE)
            return
        }
        if (isAppResumed() && currentSessionId() == sessionId) {
            seenStore.add(dedupeKey, now())
            record(sessionId, dedupeKey, DeliveryOutcome.SUPPRESSED_FOREGROUND_CURRENT)
            return
        }
        val spec = CompletionNotificationSpec(
            sessionId = sessionId,
            title = brief.title,
            brief = brief.brief,
        )
        // Mark delivered either way: a disabled channel must not loop or
        // re-notify later completions of the same turn.
        val accepted = notifyShell(spec)
        seenStore.add(dedupeKey, now())
        record(
            sessionId, dedupeKey,
            if (accepted) DeliveryOutcome.NOTIFIED else DeliveryOutcome.NOTIFICATION_DISABLED
        )
    }

    private fun record(sessionId: String, dedupeKey: String?, outcome: DeliveryOutcome) {
        receipts.addLast(DeliveryReceipt(sessionId, dedupeKey, outcome, now()))
        while (receipts.size > RECEIPT_LIMIT) receipts.removeFirst()
        if (outcome == DeliveryOutcome.FETCH_FAILED) {
            onIssue("Completion notification fetch failed for session $sessionId")
        }
    }

    private companion object {
        const val RECEIPT_LIMIT = 50
    }
}
