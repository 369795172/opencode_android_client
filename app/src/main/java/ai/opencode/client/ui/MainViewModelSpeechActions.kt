package ai.opencode.client.ui

import android.util.Log
import ai.opencode.client.data.audio.AIBuildersAudioClient
import ai.opencode.client.data.audio.AudioRecorderManager
import ai.opencode.client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

internal data class SpeechInputConfig(
    val token: String,
    val baseURL: String,
    val prompt: String,
    val terminology: String
)

internal fun currentSpeechInputConfig(settingsManager: SettingsManager): SpeechInputConfig {
    return SpeechInputConfig(
        token = AIBuildersAudioClient.sanitizeBearerToken(settingsManager.aiBuilderToken),
        baseURL = settingsManager.aiBuilderBaseURL.trim(),
        prompt = settingsManager.aiBuilderCustomPrompt.trim(),
        terminology = settingsManager.aiBuilderTerminology.trim()
    )
}

internal fun launchSpeechTranscription(
    scope: CoroutineScope,
    state: MutableStateFlow<AppState>,
    audioRecorderManager: AudioRecorderManager,
    config: SpeechInputConfig,
    recordingFile: File,
    existingInput: String,
    tag: String
) {
    scope.launch {
        try {
            Log.d(tag, "Converting recorded audio to PCM: ${recordingFile.absolutePath}")
            val pcmData = audioRecorderManager.convertToPCM(recordingFile)
            Log.d(tag, "Submitting audio for transcription: bytes=${pcmData.size}")
            val result = AIBuildersAudioClient.transcribe(
                baseURL = config.baseURL,
                token = config.token,
                pcmAudio = pcmData,
                language = null,
                prompt = config.prompt.ifEmpty { null },
                terms = config.terminology.ifEmpty { null },
                onPartialTranscript = { partial ->
                    state.update { it.copy(inputText = mergedSpeechInput(existingInput, partial)) }
                }
            )

            result.onSuccess { response ->
                val cleaned = response.text.trim()
                Log.d(tag, "Transcription success: chars=${cleaned.length}")
                state.update {
                    it.copy(
                        inputText = mergedSpeechInput(existingInput, cleaned),
                        isTranscribing = false
                    )
                }
            }.onFailure { error ->
                Log.e(tag, "Transcription failed", error)
                state.update {
                    it.copy(
                        inputText = existingInput,
                        isTranscribing = false,
                        speechError = errorMessageOrFallback(error, "Transcription failed")
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(tag, "Speech processing failed", error)
            state.update {
                it.copy(
                    inputText = existingInput,
                    isTranscribing = false,
                    speechError = errorMessageOrFallback(error, "Transcription failed")
                )
            }
        }
    }
}
