package ai.opencode.client

import ai.opencode.client.notifications.CompletionSeenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionSeenStoreTest {

    private val prefs = FakeSharedPreferences()
    private val store = CompletionSeenStore(prefs)

    @Test
    fun `added keys are visible and persist across store instances`() {
        assertFalse(store.contains("ses_a:msg_1"))
        store.add("ses_a:msg_1", nowMs = 1_000L)
        assertTrue(store.contains("ses_a:msg_1"))
        assertEquals(1, store.size)

        val reopened = CompletionSeenStore(prefs)
        assertTrue(reopened.contains("ses_a:msg_1"))
    }

    @Test
    fun `retention boundary survives and strictly older entries are pruned`() {
        val now = 100L * 24 * 60 * 60 * 1000
        val boundary = now - CompletionSeenStore.RETENTION_MS
        val stale = boundary - 1

        val kept = CompletionSeenStore.prune(
            mapOf(
                "k_boundary" to boundary,
                "k_stale" to stale,
                "k_fresh" to now,
            ),
            nowMs = now,
        )
        assertTrue(kept.containsKey("k_boundary"))
        assertTrue(kept.containsKey("k_fresh"))
        assertFalse(kept.containsKey("k_stale"))
    }

    @Test
    fun `cap keeps only the newest thousand keys`() {
        val now = 1_000_000L
        val entries = (1..1002).associate { index ->
            "ses_$index:msg_$index" to (now + index).toLong()
        }
        val pruned = CompletionSeenStore.prune(entries, nowMs = now)
        assertEquals(CompletionSeenStore.MAX_KEYS, pruned.size)
        // The two oldest (smallest timestamps) were dropped.
        assertFalse(pruned.containsKey("ses_1:msg_1"))
        assertFalse(pruned.containsKey("ses_2:msg_2"))
        assertTrue(pruned.containsKey("ses_1002:msg_1002"))
    }

    @Test
    fun `add prunes on write so the store never exceeds bounds`() {
        var now = 1_000L
        repeat(1002) { index ->
            now += 1
            store.add("ses_$index:msg_$index", now)
        }
        assertEquals(CompletionSeenStore.MAX_KEYS, store.size)
        assertFalse(store.contains("ses_0:msg_0"))
    }

    @Test
    fun `malformed persisted payload degrades to empty`() {
        prefs.edit().putString(CompletionSeenStore.KEY_SEEN, "not-json{").commit()
        val broken = CompletionSeenStore(prefs)
        assertEquals(0, broken.size)
        assertFalse(broken.contains("anything"))

        // A subsequent add recovers the store.
        broken.add("ses_x:msg_1", 5L)
        assertTrue(broken.contains("ses_x:msg_1"))
    }
}
