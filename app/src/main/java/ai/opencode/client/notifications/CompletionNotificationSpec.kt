package ai.opencode.client.notifications

/**
 * Pure, JVM-testable blueprint of a task-completion notification.
 *
 * The caller (completion detector + notifier plugin, later todos) supplies
 * values that are ALREADY privacy-normalized and capped by
 * [ai.opencode.client.data.model.CompletionBrief]; this shell never
 * re-normalizes and never widens exposure: the lock-screen public version
 * carries ONLY [title], never the raw OpenCode title, workspace paths, or
 * the brief body.
 *
 * Notification ids and PendingIntent request codes are deterministic
 * functions of the session id, so they are stable across process restarts
 * (a repost after process death updates the same notification instead of
 * duplicating it) and occupy dedicated ranges that cannot collide with the
 * small hardcoded codes used by TtsService (0-3) or the service notification
 * ids (9001/9002).
 *
 * Extension points reserved for the notification-action todo (NOT implemented
 * here): [REQUEST_SLOT_READ_BRIEF] and [REQUEST_SLOT_READ_ALOUD] action
 * slots. When those actions arrive, their intents may carry at most the
 * capped brief extra for local playback; the full session text must never
 * ride along in any PendingIntent.
 */
data class CompletionNotificationSpec(
    val sessionId: String,
    val title: String,
    val brief: String,
) {
    /** Text of the lock-screen public version: the normalized title ONLY. */
    val publicVersionText: String
        get() = title

    /** Stable per-session notification id in the dedicated 9100+ range. */
    val notificationId: Int
        get() = notificationIdFor(sessionId)

    /** Stable per-session request code for the content-tap PendingIntent. */
    val contentRequestCode: Int
        get() = requestCodeFor(REQUEST_SLOT_CONTENT, sessionId)

    companion object {
        const val NOTIFICATION_ID_BASE = 9100
        private const val NOTIFICATION_ID_SPAN = 0x100

        // Dedicated "task completion" request-code namespace ("TCM."), far
        // away from any legacy small request code in the app.
        private const val REQUEST_CODE_BASE = 0x54434D00
        private const val REQUEST_CODE_SPAN = 0x100000

        /** Content-tap PendingIntent slot (deep-link routing lands in a later todo). */
        const val REQUEST_SLOT_CONTENT = 0

        /** Reserved action slot: "Read brief" (implemented in a later todo). */
        const val REQUEST_SLOT_READ_BRIEF = 1

        /** Reserved action slot: "Read aloud" local playback (implemented in a later todo). */
        const val REQUEST_SLOT_READ_ALOUD = 2

        fun notificationIdFor(sessionId: String): Int =
            NOTIFICATION_ID_BASE + (fmix32(sessionId.hashCode()) and (NOTIFICATION_ID_SPAN - 1))

        fun requestCodeFor(slot: Int, sessionId: String): Int =
            REQUEST_CODE_BASE + (
                fmix32(sessionId.hashCode() xor (slot * 0x9E3779B1.toInt())) and (REQUEST_CODE_SPAN - 1)
                )

        /** MurmurHash3 32-bit finalizer; spreads session-id hashes into the id/code ranges. */
        private fun fmix32(seed: Int): Int {
            var h = seed
            h = h xor (h ushr 16); h *= 0x85EBCA6B.toInt()
            h = h xor (h ushr 13); h *= 0xC2B2AE35.toInt()
            h = h xor (h ushr 16)
            return h
        }
    }
}
