package ai.opencode.client

import android.app.PendingIntent
import ai.opencode.client.data.model.CompletionBrief
import ai.opencode.client.notifications.CompletionNotificationSpec
import ai.opencode.client.notifications.CompletionNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionNotifierTest {

    @Test
    fun `channel id is the dedicated task completion channel`() {
        assertEquals("task_completion_v1", CompletionNotifier.CHANNEL_ID)
    }

    @Test
    fun `pending intents are immutable and update-safe`() {
        assertEquals(
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            CompletionNotifier.PENDING_INTENT_FLAGS
        )
    }

    @Test
    fun `public version exposes only the normalized title`() {
        val rawTitle = "Fix login bug in /home/alice/secret-project and /tmp/build.log"
        val brief = CompletionBrief.extract("""{"id":"ses_public","messages":[]}""", rawTitle)
        assertTrue(brief.title.contains("[path]"))

        // Shell contract: the input is the PREPARED normalized title/brief.
        val spec = CompletionNotificationSpec(
            sessionId = "ses_public",
            title = brief.title,
            brief = brief.brief,
        )

        assertEquals(brief.title, spec.publicVersionText)
        assertFalse(spec.publicVersionText.contains("/home/alice"))
        assertFalse(spec.publicVersionText.contains("secret-project"))
        assertFalse(spec.publicVersionText.contains("/tmp/build.log"))
        assertFalse(spec.publicVersionText.contains(rawTitle))
    }

    @Test
    fun `private body carries the capped brief and never leaks into the public version`() {
        val payload = """
            {"id":"ses_body","messages":[
              {"info":{"role":"assistant","id":"msg_1"},
               "parts":[{"type":"text","text":"Done. Report saved at file:///home/user/report.md"}]}
            ]}
        """.trimIndent()
        val brief = CompletionBrief.extract(payload, "Refactor API")
        assertTrue(brief.brief.contains("Done"))
        assertFalse(brief.brief.contains("file://"))

        val spec = CompletionNotificationSpec(
            sessionId = "ses_body",
            title = brief.title,
            brief = brief.brief,
        )

        assertEquals(brief.brief, spec.brief)
        assertEquals(brief.title, spec.title)
        assertEquals(spec.title, spec.publicVersionText)
        assertFalse(spec.publicVersionText.contains("Done"))
        assertFalse(spec.publicVersionText.contains("report"))
    }

    @Test
    fun `request codes are stable across calls`() {
        val first = CompletionNotificationSpec.requestCodeFor(
            CompletionNotificationSpec.REQUEST_SLOT_CONTENT, "ses_stable"
        )
        val second = CompletionNotificationSpec.requestCodeFor(
            CompletionNotificationSpec.REQUEST_SLOT_CONTENT, "ses_stable"
        )
        assertEquals(first, second)
        assertEquals(
            first,
            CompletionNotificationSpec(sessionId = "ses_stable", title = "t", brief = "b")
                .contentRequestCode
        )
    }

    @Test
    fun `request codes differ per session and per action slot`() {
        val sessionIds = listOf(
            "ses_a", "ses_b", "ses_c", "ses_d", "ses_long_session_identifier_1", "ses_x"
        )
        val perSession = sessionIds.map {
            CompletionNotificationSpec.requestCodeFor(
                CompletionNotificationSpec.REQUEST_SLOT_CONTENT, it
            )
        }
        assertEquals(perSession.size, perSession.toSet().size)

        val slots = listOf(
            CompletionNotificationSpec.REQUEST_SLOT_CONTENT,
            CompletionNotificationSpec.REQUEST_SLOT_READ_BRIEF,
            CompletionNotificationSpec.REQUEST_SLOT_READ_ALOUD,
        )
        val perSlot = slots.map { CompletionNotificationSpec.requestCodeFor(it, "ses_a") }
        assertEquals(slots.size, perSlot.toSet().size)
    }

    @Test
    fun `request codes live in a dedicated range away from legacy service codes`() {
        val sessions = listOf("ses_a", "ses_b", "ses_c", "ses_d", "ses_e", "ses_f")
        for (session in sessions) {
            for (slot in 0..2) {
                val code = CompletionNotificationSpec.requestCodeFor(slot, session)
                assertTrue(code >= 0x54434D00)
                // TtsService uses request codes 0-3; never overlap them.
                assertFalse(code in 0..16)
            }
        }
    }

    @Test
    fun `notification ids are stable, per-session, and outside service notification ids`() {
        assertEquals(
            CompletionNotificationSpec.notificationIdFor("ses_a"),
            CompletionNotificationSpec.notificationIdFor("ses_a")
        )
        val ids = listOf("ses_a", "ses_b", "ses_c", "ses_d", "ses_e", "ses_f", "ses_g", "ses_h")
            .map { CompletionNotificationSpec.notificationIdFor(it) }
        assertEquals(ids.size, ids.toSet().size)
        // TtsService posts at 9001 and SpeechSessionService at 9002.
        ids.forEach { assertTrue(it in 9100..9355) }
    }

    @Test
    fun `deep link uri is produced only for valid ses session ids`() {
        assertEquals(
            "opencode://session/ses_target_1",
            CompletionNotificationSpec.deepLinkUriFor("ses_target_1")
        )
        assertNull(CompletionNotificationSpec.deepLinkUriFor("not-valid"))
        assertNull(CompletionNotificationSpec.deepLinkUriFor("ses_"))
        assertNull(CompletionNotificationSpec.deepLinkUriFor("ses_a/b"))
        assertNull(CompletionNotificationSpec.deepLinkUriFor("ses_has space"))
        assertNull(CompletionNotificationSpec.deepLinkUriFor("ses_query?x=1"))
        assertNull(CompletionNotificationSpec.deepLinkUriFor("ses_frag#f"))
    }

    @Test
    fun `spec exposes its deep link and falls back to null for malformed ids`() {
        assertEquals(
            "opencode://session/ses_ok",
            CompletionNotificationSpec(sessionId = "ses_ok", title = "t", brief = "b").deepLinkUri
        )
        assertNull(
            CompletionNotificationSpec(sessionId = "not_valid", title = "t", brief = "b").deepLinkUri
        )
    }

    @Test
    fun `assistant message id rides along with a null default`() {
        val plain = CompletionNotificationSpec(sessionId = "ses_a", title = "t", brief = "b")
        assertNull(plain.assistantMessageId)
        val withId = plain.copy(assistantMessageId = "msg_1")
        assertEquals("msg_1", withId.assistantMessageId)
        // Equality/default keeps existing todo-5 constructions source-compatible.
        assertEquals(plain, CompletionNotificationSpec(sessionId = "ses_a", title = "t", brief = "b"))
    }

    @Test
    fun `play request code uses the read aloud slot and is deterministic`() {
        val spec = CompletionNotificationSpec(sessionId = "ses_play", title = "t", brief = "b")
        assertEquals(
            CompletionNotificationSpec.requestCodeFor(
                CompletionNotificationSpec.REQUEST_SLOT_READ_ALOUD, "ses_play"
            ),
            spec.playRequestCode
        )
        assertFalse(spec.playRequestCode == spec.contentRequestCode)
    }
}
