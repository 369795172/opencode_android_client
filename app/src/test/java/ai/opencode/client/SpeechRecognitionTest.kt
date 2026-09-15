package ai.opencode.client

import ai.opencode.client.ui.AppState
import ai.opencode.client.ui.mergedSpeechInput
import ai.opencode.client.ui.sanitizeBearerToken
import ai.opencode.client.ui.speechErrorMessage
import ai.opencode.client.ui.speechFailureInput
import com.yage.voiceflowkit.VoiceFlowError
import org.junit.Assert.*
import org.junit.Test

/**
 * Speech-input UI-glue tests. The realtime audio pipeline (URL building, WAV writing,
 * the disk cache, PCM resampling, the WebSocket session) moved into the VoiceFlowKit
 * library and is covered by that module's own unit tests; only the opencode-side glue
 * (token sanitizing + transcript merging + AppState defaults) is exercised here.
 */
class SpeechRecognitionTest {

    // ─── sanitizeBearerToken ─────────────────────────────────────────

    @Test
    fun `sanitizeBearerToken strips surrounding and hidden whitespace`() {
        val raw = "  abc​123﻿  "
        assertEquals("abc123", sanitizeBearerToken(raw))
    }

    @Test
    fun `sanitizeBearerToken keeps normal token characters`() {
        val raw = "sk-proj_ABC-123.xyz"
        assertEquals(raw, sanitizeBearerToken(raw))
    }

    // ─── mergedSpeechInput ───────────────────────────────────────────

    @Test
    fun `mergedSpeechInput both non-empty`() {
        assertEquals("Hello world", mergedSpeechInput("Hello", "world"))
    }

    @Test
    fun `mergedSpeechInput empty prefix`() {
        assertEquals("world", mergedSpeechInput("", "world"))
    }

    @Test
    fun `mergedSpeechInput empty transcript`() {
        assertEquals("Hello", mergedSpeechInput("Hello", ""))
    }

    @Test
    fun `mergedSpeechInput both empty`() {
        assertEquals("", mergedSpeechInput("", ""))
    }

    @Test
    fun `mergedSpeechInput trims transcript whitespace`() {
        assertEquals("Hello world", mergedSpeechInput("Hello", "  world  "))
    }

    @Test
    fun `mergedSpeechInput whitespace-only transcript returns prefix`() {
        assertEquals("Hello", mergedSpeechInput("Hello", "   "))
    }

    @Test
    fun `speechFailureInput preserves visible partial transcript`() {
        assertEquals("Hello partial result", speechFailureInput("Hello", "Hello partial result"))
        assertEquals("partial result", speechFailureInput("", "partial result"))
    }

    @Test
    fun `speechFailureInput falls back to existing input without partial transcript`() {
        assertEquals("Hello", speechFailureInput("Hello", "   "))
    }

    // ─── speechErrorMessage ──────────────────────────────────────────

    @Test
    fun `speechErrorMessage empty transcript mentions retry when audio is preserved`() {
        val message = speechErrorMessage(VoiceFlowError.EmptyTranscript, retryAvailable = true)
        assertTrue(message.contains("录音已保留"))
        assertTrue(message.contains("重试"))
    }

    @Test
    fun `speechErrorMessage empty transcript without preserved audio asks to re-record`() {
        val message = speechErrorMessage(VoiceFlowError.EmptyTranscript, retryAvailable = false)
        assertTrue(message.contains("未识别到语音"))
        assertFalse(message.contains("录音已保留"))
    }

    @Test
    fun `speechErrorMessage empty grok recording maps to re-record guidance`() {
        val message = speechErrorMessage(IllegalStateException("Empty Grok recording"), retryAvailable = false)
        assertEquals("本次没有录到音频，请重试录音", message)
    }

    @Test
    fun `speechErrorMessage http and transport errors carry status or detail`() {
        assertEquals("语音服务返回 HTTP 502", speechErrorMessage(VoiceFlowError.HttpError(502), retryAvailable = false))
        assertEquals(
            "语音连接中断：socket closed",
            speechErrorMessage(VoiceFlowError.ConnectionLost("socket closed"), retryAvailable = false)
        )
        assertEquals(
            "语音通道错误：bad frame",
            speechErrorMessage(VoiceFlowError.WebsocketError("bad frame"), retryAvailable = false)
        )
    }

    @Test
    fun `speechErrorMessage unknown errors fall back to their message`() {
        assertEquals("boom", speechErrorMessage(IllegalStateException("boom"), retryAvailable = true))
        assertEquals("Transcription failed", speechErrorMessage(null, retryAvailable = false))
    }

    // ─── AppState speech defaults ────────────────────────────────────

    @Test
    fun `AppState speech fields have correct defaults`() {
        val state = AppState()
        assertFalse(state.isRecording)
        assertFalse(state.isTranscribing)
        assertFalse(state.hasPreservedSpeechAudio)
        assertFalse(state.isRetryingSpeech)
        assertNull(state.speechError)
        assertFalse(state.aiBuilderConnectionOK)
        assertNull(state.aiBuilderConnectionError)
        assertFalse(state.isTestingAIBuilderConnection)
    }

    @Test
    fun `AppState speech fields can be set`() {
        val state = AppState(
            isRecording = true,
            isTranscribing = true,
            hasPreservedSpeechAudio = true,
            isRetryingSpeech = true,
            speechError = "mic failed",
            aiBuilderConnectionOK = true,
            aiBuilderConnectionError = "timeout",
            isTestingAIBuilderConnection = true
        )
        assertTrue(state.isRecording)
        assertTrue(state.isTranscribing)
        assertTrue(state.hasPreservedSpeechAudio)
        assertTrue(state.isRetryingSpeech)
        assertEquals("mic failed", state.speechError)
        assertTrue(state.aiBuilderConnectionOK)
        assertEquals("timeout", state.aiBuilderConnectionError)
        assertTrue(state.isTestingAIBuilderConnection)
    }
}
