package ai.opencode.client

import ai.opencode.client.tts.TtsSpeakPayload
import ai.opencode.client.tts.resolveSpeakRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSpeakRequestResolutionTest {

    private val staged = TtsSpeakPayload(text = "STALE staged text", messageId = "msg_stale", speechRate = 1.5f)

    @Test
    fun `explicit notification extras win over a staged payload`() {
        val resolution = resolveSpeakRequest(
            explicitText = "Notification brief text",
            explicitMessageId = "msg_target",
            staged = staged,
        )
        assertEquals("Notification brief text", resolution.text)
        assertEquals("msg_target", resolution.messageId)
        assertFalse(resolution.consumeStaged)
    }

    @Test
    fun `staged payload is used only when the request carries no explicit text`() {
        val resolution = resolveSpeakRequest(
            explicitText = null,
            explicitMessageId = "msg_from_controller_intent",
            staged = staged,
        )
        assertEquals("STALE staged text", resolution.text)
        assertEquals("msg_stale", resolution.messageId)
        assertEquals(1.5f, resolution.speechRate!!)
        assertTrue(resolution.consumeStaged)
    }

    @Test
    fun `blank explicit text counts as absent and falls back to staged`() {
        val resolution = resolveSpeakRequest(
            explicitText = "   ",
            explicitMessageId = "msg_x",
            staged = staged,
        )
        assertEquals("STALE staged text", resolution.text)
        assertTrue(resolution.consumeStaged)
    }

    @Test
    fun `no explicit text and nothing staged resolves to a blank request`() {
        val resolution = resolveSpeakRequest(
            explicitText = null,
            explicitMessageId = null,
            staged = null,
        )
        assertNull(resolution.text)
        assertNull(resolution.messageId)
        assertTrue(resolution.consumeStaged)
    }

    @Test
    fun `explicit path leaves speech rate to the intent extras`() {
        val resolution = resolveSpeakRequest(
            explicitText = "brief",
            explicitMessageId = "msg_t",
            staged = staged,
        )
        assertNull(resolution.speechRate)
        assertFalse(resolution.consumeStaged)
    }
}
