package ai.opencode.client.notifications

import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Bounded persisted seen-set of delivered completion dedupe keys
 * ("<session id>:<assistant message id>"), used to suppress duplicate
 * notifications across idle replays, SSE reconnects, and process restarts.
 *
 * Only the key and its delivery timestamp (epoch ms) are persisted — never
 * the brief, title, or any session text. Retention is bounded both by age
 * (30 days; an entry at exactly the boundary survives, strictly older is
 * pruned) and by count (at most [MAX_KEYS]; the oldest are pruned first).
 */
class CompletionSeenStore(private val prefs: SharedPreferences) {

    fun contains(key: String): Boolean = readAll().containsKey(key)

    /** Records the key at [nowMs] and prunes retention/count bounds. */
    fun add(key: String, nowMs: Long) {
        val next = prune(readAll() + (key to nowMs), nowMs)
        prefs.edit().putString(KEY_SEEN, json.encodeToString(next)).apply()
    }

    val size: Int get() = readAll().size

    private fun readAll(): Map<String, Long> {
        val raw = prefs.getString(KEY_SEEN, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Long>>(raw) }
            .getOrDefault(emptyMap())
    }

    companion object {
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_KEYS = 1000
        internal const val KEY_SEEN = "delivered_keys"
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Pure pruning used by [add]: drops entries strictly older than the
         * 30-day window (the boundary itself survives), then keeps only the
         * [MAX_KEYS] newest entries (ties broken by key for determinism).
         */
        fun prune(entries: Map<String, Long>, nowMs: Long): Map<String, Long> {
            val fresh = entries.filterValues { it >= nowMs - RETENTION_MS }
            if (fresh.size <= MAX_KEYS) return fresh
            return fresh.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(MAX_KEYS)
                .associate { it.key to it.value }
        }
    }
}
