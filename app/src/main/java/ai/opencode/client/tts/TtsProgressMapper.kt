package ai.opencode.client.tts

internal object TtsProgressMapper {

    fun chunkIndexForProgress(progress: Float, chunkCount: Int): Int {
        if (chunkCount <= 1) return 0
        return (progress.coerceIn(0f, 1f) * (chunkCount - 1)).toInt().coerceIn(0, chunkCount - 1)
    }
}
