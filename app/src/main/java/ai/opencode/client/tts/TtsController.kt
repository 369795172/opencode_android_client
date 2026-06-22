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
    val messageId: String? = null
)

@Singleton
class TtsController(
    private val context: Context
) {
    private val _playbackState = MutableStateFlow(TtsPlaybackState())
    val playbackState: StateFlow<TtsPlaybackState> = _playbackState.asStateFlow()

    fun speak(text: String, messageId: String? = null) {
        val cleaned = stripMarkdown(text)
        if (cleaned.isBlank()) return

        val intent = Intent(context, TtsService::class.java).apply {
            action = TtsService.ACTION_SPEAK
            putExtra(TtsService.EXTRA_TEXT, cleaned)
            putExtra(TtsService.EXTRA_MESSAGE_ID, messageId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _playbackState.value = TtsPlaybackState(isPlaying = true, messageId = messageId)
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

    internal fun onPlaybackStarted(messageId: String?) {
        _playbackState.value = TtsPlaybackState(isPlaying = true, messageId = messageId)
    }

    internal fun onPlaybackPaused(messageId: String?) {
        _playbackState.value = TtsPlaybackState(isPlaying = false, isPaused = true, messageId = messageId)
    }

    internal fun onPlaybackStopped() {
        _playbackState.value = TtsPlaybackState()
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
