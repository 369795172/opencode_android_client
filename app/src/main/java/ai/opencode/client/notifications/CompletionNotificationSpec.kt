package ai.opencode.client.notifications

import ai.opencode.client.util.OpenCodeDeepLinkParseResult
import ai.opencode.client.util.OpenCodeDeepLinkParser

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
 * Action surface: the content tap deep-links to `opencode://session/<id>`
 * (only when the id parses as a valid `ses_…` session link — anything else
 * keeps the generic launch intent, so malformed ids can never produce a
 * broken navigation). The "Play brief" action may carry at most
 * [assistantMessageId] plus the capped brief for local playback; the full
 * session text must never ride along in any PendingIntent.
 */
data class CompletionNotificationSpec(
    val sessionId: String,
    val title: String,
    val brief: String,
    val assistantMessageId: String? = null,
) {
    /** Text of the lock-screen public version: the normalized title ONLY. */
    val publicVersionText: String
        get() = title

    /**
     * The `opencode://session/<id>` deep link, or null when the session id
     * is not a valid read-only session link (the caller then falls back to
     * the generic launch intent instead of attaching a broken URI).
     */
    val deepLinkUri: String?
        get() = deepLinkUriFor(sessionId)

    /** Stable per-session notification id in the dedicated 9100+ range. */
    val notificationId: Int
        get() = notificationIdFor(sessionId)

    /** Stable per-session request code for the content-tap PendingIntent. */
    val contentRequestCode: Int
        get() = requestCodeFor(REQUEST_SLOT_CONTENT, sessionId)

    /** Stable per-session request code for the "Play brief" action PendingIntent. */
    val playRequestCode: Int
        get() = requestCodeFor(REQUEST_SLOT_READ_ALOUD, sessionId)

    companion object {
        const val NOTIFICATION_ID_BASE = 9100
        private const val NOTIFICATION_ID_SPAN = 0x100

        // Dedicated "task completion" request-code namespace ("TCM."), far
        // away from any legacy small request code in the app.
        private const val REQUEST_CODE_BASE = 0x54434D00
        private const val REQUEST_CODE_SPAN = 0x100000

        /** Content-tap PendingIntent slot (the per-session deep-link route). */
        const val REQUEST_SLOT_CONTENT = 0

        /** Reserved action slot: "Read brief" (implemented in a later todo). */
        const val REQUEST_SLOT_READ_BRIEF = 1

        /** "Play brief" local playback action slot. */
        const val REQUEST_SLOT_READ_ALOUD = 2

        /**
         * The session deep link for [sessionId], or null when the id cannot
         * form a valid `opencode://session/ses_…` link.
         */
        fun deepLinkUriFor(sessionId: String): String? {
            if (!sessionId.startsWith("ses_") || sessionId.length <= 4) return null
            val uri = "$SCHEME_SESSION://session/$sessionId"
            return if (OpenCodeDeepLinkParser.parse(uri) is OpenCodeDeepLinkParseResult.Success) {
                uri
            } else {
                null
            }
        }

        fun notificationIdFor(sessionId: String): Int =
            NOTIFICATION_ID_BASE + (fmix32(sessionId.hashCode()) and (NOTIFICATION_ID_SPAN - 1))

        fun requestCodeFor(slot: Int, sessionId: String): Int =
            REQUEST_CODE_BASE + (
                fmix32(sessionId.hashCode() xor (slot * 0x9E3779B1.toInt())) and (REQUEST_CODE_SPAN - 1)
                )

        private const val SCHEME_SESSION = "opencode"

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
