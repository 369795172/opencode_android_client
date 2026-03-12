package com.yage.opencode_client.data.audio

internal object AudioRecorderConfig {
    const val outputSampleRate = 44_100
    const val outputChannelCount = 1
    const val outputBitRate = 64_000
    const val targetPcmSampleRate = 24_000
    const val codecTimeoutUs = 10_000L
    const val tempFilePrefix = "opencode-recording-"
    const val tempFileSuffix = ".m4a"
}

internal object AudioTranscriptionConfig {
    const val connectTimeoutSeconds = 15L
    const val readTimeoutSeconds = 60L
    const val writeTimeoutSeconds = 60L
    const val sendChunkSizeBytes = 240_000
    const val silenceDurationMs = 1_200
    const val jsonMediaType = "application/json; charset=utf-8"
}
