package ai.opencode.client.tts

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Singleton

data class TtsPlaybackState(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val messageId: String? = null,
    val progress: Float = 0f,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val speechRate: Float = 1f,
)

internal data class TtsSpeakPayload(
    val text: String,
    val messageId: String?,
    val speechRate: Float,
)

@Singleton
class TtsController(
    private val context: Context,
) {
    private val _playbackState = MutableStateFlow(TtsPlaybackState())
    val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()
    @Volatile
    private var pendingSpeakPayload: TtsSpeakPayload? = null

    fun speak(text: String, messageId: String? = null, speechRate: Float = 1f) {
        val cleaned = stripMarkdown(text)
        if (cleaned.isBlank()) return

        pendingSpeakPayload = TtsSpeakPayload(cleaned, messageId, speechRate)
        val intent = Intent(context, TtsService::class.java).apply {
            action = TtsService.ACTION_SPEAK
            putExtra(TtsService.EXTRA_MESSAGE_ID, messageId)
            putExtra(TtsService.EXTRA_SPEECH_RATE, speechRate)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _playbackState.value = TtsPlaybackState(
            isPlaying = true,
            messageId = messageId,
            speechRate = speechRate,
        )
    }

    fun stop() {
        val intent = Intent(context, TtsService::class.java).apply {
            action = TtsService.ACTION_STOP
        }
        context.startService(intent)
        _playbackState.value = TtsPlaybackState()
    }

    fun pause() {
        val intent = Intent(context, TtsService::class.java).apply {
            action = TtsService.ACTION_PAUSE
        }
        context.startService(intent)
        _playbackState.update { it.copy(isPlaying = false, isPaused = true) }
    }

    fun resume() {
        val intent = Intent(context, TtsService::class.java).apply {
            action = TtsService.ACTION_RESUME
        }
        context.startService(intent)
        _playbackState.update { it.copy(isPlaying = true, isPaused = false) }
    }

    fun seek(progress: Float) {
        val intent = Intent(context, TtsService::class.java).apply {
            action = TtsService.ACTION_SEEK
            putExtra(TtsService.EXTRA_PROGRESS, progress.coerceIn(0f, 1f))
        }
        context.startService(intent)
        _playbackState.update { it.copy(progress = progress.coerceIn(0f, 1f)) }
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 2.5f)
        val intent = Intent(context, TtsService::class.java).apply {
            action = TtsService.ACTION_SET_SPEED
            putExtra(TtsService.EXTRA_SPEECH_RATE, clamped)
        }
        context.startService(intent)
        _playbackState.update { it.copy(speechRate = clamped) }
    }

    internal fun onPlaybackStarted(messageId: String?) {
        _playbackState.update {
            it.copy(isPlaying = true, isPaused = false, messageId = messageId)
        }
    }

    internal fun onPlaybackProgress(
        messageId: String?,
        chunkIndex: Int,
        totalChunks: Int,
        progress: Float,
        isPlaying: Boolean,
        isPaused: Boolean,
    ) {
        _playbackState.update {
            it.copy(
                messageId = messageId ?: it.messageId,
                currentChunkIndex = chunkIndex,
                totalChunks = totalChunks,
                progress = progress.coerceIn(0f, 1f),
                isPlaying = isPlaying,
                isPaused = isPaused,
            )
        }
    }

    internal fun onPlaybackPaused(messageId: String?) {
        _playbackState.update {
            it.copy(isPlaying = false, isPaused = true, messageId = messageId)
        }
    }

    internal fun onPlaybackStopped() {
        _playbackState.value = TtsPlaybackState(speechRate = _playbackState.value.speechRate)
    }

    internal fun onSpeechRateChanged(rate: Float) {
        _playbackState.update { it.copy(speechRate = rate) }
    }

    internal fun consumePendingSpeakPayload(): TtsSpeakPayload? {
        val payload = pendingSpeakPayload
        pendingSpeakPayload = null
        return payload
    }

    companion object {
        fun stripMarkdown(text: String): String {
            var result = text
            result = result.replace(Regex("```[\\s\\S]*?```"), " ")
            result = result.replace(Regex("`([^`]+)`"), "$1")
            result = result.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            result = result.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            result = result.replace(Regex("__([^_]+)__"), "$1")
            result = result.replace(Regex("\\*([^*]+)\\*"), "$1")
            result = result.replace(Regex("_([^_]+)_"), "$1")
            result = result.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
            return result.replace(Regex("\\s+"), " ").trim()
        }
    }
}
