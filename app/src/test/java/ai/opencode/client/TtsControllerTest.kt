package ai.opencode.client

import ai.opencode.client.tts.TtsController
import ai.opencode.client.tts.TtsReplayBuffer
import ai.opencode.client.tts.TtsResumeMode
import ai.opencode.client.tts.TtsSpeakPayload
import ai.opencode.client.tts.resolveTtsResumeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TtsControllerTest {

    @Test
    fun stripMarkdown_removesCommonMarkers() {
        val input = "# Title\n\nHello **world** and `code` with [link](https://example.com)."
        val result = TtsController.stripMarkdown(input)
        assertEquals("Title Hello world and code with link.", result)
    }

    @Test
    fun stripMarkdown_removesCodeBlocks() {
        val input = "Before\n```kotlin\nval x = 1\n```\nAfter"
        val result = TtsController.stripMarkdown(input)
        assertEquals("Before After", result)
    }

    @Test
    fun `consumed payload remains resumable until playback stops`() {
        val buffer = TtsReplayBuffer()
        val payload = TtsSpeakPayload("Read this after leaving the session", "message-1", 1.25f)

        buffer.enqueue(payload)

        assertEquals(payload, buffer.consumePending())
        assertNotNull(buffer.resumePayload())
        assertEquals(payload, buffer.resumePayload())

        buffer.clear()
        assertNull(buffer.resumePayload())
    }

    @Test
    fun `resume mode distinguishes paused recreated and already playing services`() {
        assertEquals(TtsResumeMode.RESUME_CHUNKS, resolveTtsResumeMode(true, true, true))
        assertEquals(TtsResumeMode.REPLAY_PAYLOAD, resolveTtsResumeMode(false, false, false))
        assertEquals(TtsResumeMode.IGNORE_ALREADY_PLAYING, resolveTtsResumeMode(false, true, true))
    }
}
