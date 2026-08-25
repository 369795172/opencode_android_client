package ai.opencode.client.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSendAfterSpeechTest {

    @Test
    fun `sends when enabled with clean transcript and open session`() {
        assertTrue(
            shouldAutoSendAfterSpeech(
                enabled = true,
                inputText = "帮我总结一下今天的任务",
                speechError = null,
                isRecording = false,
                isTranscribing = false,
                hasSession = true,
            )
        )
    }

    @Test
    fun `disabled never sends`() {
        assertFalse(
            shouldAutoSendAfterSpeech(
                enabled = false,
                inputText = "hello",
                speechError = null,
                isRecording = false,
                isTranscribing = false,
                hasSession = true,
            )
        )
    }

    @Test
    fun `transcription failure blocks send`() {
        assertFalse(
            shouldAutoSendAfterSpeech(
                enabled = true,
                inputText = "partial text",
                speechError = "Transcription failed",
                isRecording = false,
                isTranscribing = false,
                hasSession = true,
            )
        )
    }

    @Test
    fun `blank transcript never sends`() {
        assertFalse(
            shouldAutoSendAfterSpeech(
                enabled = true,
                inputText = "   ",
                speechError = null,
                isRecording = false,
                isTranscribing = false,
                hasSession = true,
            )
        )
    }

    @Test
    fun `still recording or transcribing blocks send`() {
        assertFalse(
            shouldAutoSendAfterSpeech(
                enabled = true,
                inputText = "hello",
                speechError = null,
                isRecording = true,
                isTranscribing = false,
                hasSession = true,
            )
        )
        assertFalse(
            shouldAutoSendAfterSpeech(
                enabled = true,
                inputText = "hello",
                speechError = null,
                isRecording = false,
                isTranscribing = true,
                hasSession = true,
            )
        )
    }

    @Test
    fun `no open session blocks send`() {
        assertFalse(
            shouldAutoSendAfterSpeech(
                enabled = true,
                inputText = "hello",
                speechError = null,
                isRecording = false,
                isTranscribing = false,
                hasSession = false,
            )
        )
    }
}
