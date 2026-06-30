package ai.opencode.client.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import ai.opencode.client.MainActivity
import ai.opencode.client.R
import ai.opencode.client.di.TtsServiceEntryPoint
import dagger.hilt.android.EntryPointAccessors

class TtsService : Service() {

    private var tts: TextToSpeech? = null
    private var mediaSession: MediaSession? = null
    private var ttsController: TtsController? = null
    private var isTtsReady = false
    private var pendingText: String? = null
    private var pendingMessageId: String? = null
    private var isPaused = false
    private var currentText: String? = null
    private var currentMessageId: String? = null
    private var selectedEngine: String? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var utteranceChunks: List<String> = emptyList()
    private var activeChunkIndex: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ensureForeground(isPlaying = false)

        ttsController = EntryPointAccessors.fromApplication(
            applicationContext,
            TtsServiceEntryPoint::class.java
        ).ttsController()

        setupMediaSession()

        selectedEngine = TtsEngineResolver.resolveEnginePackage(applicationContext)
        if (selectedEngine == null) {
            Log.w(TAG, "No TTS engine installed on device")
            notifyTtsError("未找到文字转语音引擎。请到 设置 → 无障碍 → 文字转语音输出 安装引擎并下载中文语音包。")
            handleTtsInitFailure()
            return
        }
        Log.i(TAG, "Using TTS engine: $selectedEngine")

        tts = TextToSpeech(applicationContext, { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech init failed engine=$selectedEngine status=$status")
                notifyTtsError("TTS 引擎初始化失败，请检查系统文字转语音设置。")
                handleTtsInitFailure()
                return@TextToSpeech
            }
            val locale = TtsEngineResolver.configureLanguage(tts ?: return@TextToSpeech)
            if (locale == null) {
                Log.w(TAG, "No supported TTS language for engine=$selectedEngine")
                notifyTtsError("未找到可用语音包。请到 设置 → 无障碍 → 文字转语音输出 下载中文语音。")
                handleTtsInitFailure()
                return@TextToSpeech
            }
            Log.i(TAG, "TTS ready engine=$selectedEngine locale=$locale")
            isTtsReady = true
            val text = pendingText
            if (text != null) {
                speakInternal(text, pendingMessageId)
                pendingText = null
                pendingMessageId = null
            }
        }, selectedEngine)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isPaused = false
                parseChunkIndex(utteranceId)?.let { activeChunkIndex = it }
                ttsController?.onPlaybackStarted(currentMessageId)
                updatePlaybackState(PlaybackState.STATE_PLAYING)
                updateForegroundNotification(isPlaying = true)
            }

            override fun onDone(utteranceId: String?) {
                val chunkIndex = parseChunkIndex(utteranceId)
                if (chunkIndex == null || chunkIndex >= utteranceChunks.lastIndex) {
                    finishPlayback()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS utterance error (legacy): utteranceId=$utteranceId")
                finishPlayback()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS utterance error: utteranceId=$utteranceId code=$errorCode")
                finishPlayback()
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground(isPlaying = false)
        when (intent?.action) {
            ACTION_STOP -> stopPlayback(removeNotification = true)
            ACTION_SPEAK -> {
                val payload = ttsController?.consumePendingSpeakPayload()
                val text = payload?.text ?: intent.getStringExtra(EXTRA_TEXT)
                if (text.isNullOrBlank()) {
                    stopPlayback(removeNotification = true)
                    return START_NOT_STICKY
                }
                if (selectedEngine == null || tts == null) {
                    notifyTtsError("文字转语音不可用，请检查系统 TTS 设置。")
                    stopPlayback(removeNotification = true)
                    return START_NOT_STICKY
                }
                val messageId = payload?.messageId ?: intent.getStringExtra(EXTRA_MESSAGE_ID)
                isPaused = false
                tts?.stop()
                currentText = text
                currentMessageId = messageId
                if (isTtsReady) {
                    speakInternal(text, messageId)
                } else {
                    pendingText = text
                    pendingMessageId = messageId
                }
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun notifyTtsError(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleTtsInitFailure() {
        pendingText = null
        pendingMessageId = null
        ttsController?.onPlaybackStopped()
        stopPlayback(removeNotification = true)
    }

    private fun ensureForeground(isPlaying: Boolean) {
        val notification = buildNotification(isPlaying)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun requestPlaybackFocus(): Boolean {
        val audioManager = getSystemService(AudioManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val audioManager = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun speakInternal(text: String, messageId: String?) {
        currentText = text
        currentMessageId = messageId
        utteranceChunks = TtsTextChunker.chunk(text, DEFAULT_MAX_SPEECH_INPUT_LENGTH)
        activeChunkIndex = 0
        ensureForeground(isPlaying = true)
        if (!requestPlaybackFocus()) {
            Log.w(TAG, "Audio focus not granted; attempting speak anyway")
        }
        Log.i(TAG, "speak len=${text.length} chunks=${utteranceChunks.size} engine=$selectedEngine")
        enqueueChunksFrom(startIndex = 0)
    }

    private fun enqueueChunksFrom(startIndex: Int) {
        val engine = tts ?: return
        if (startIndex >= utteranceChunks.size) {
            finishPlayback()
            return
        }
        for (index in startIndex until utteranceChunks.size) {
            val chunk = utteranceChunks[index]
            val mode = if (index == startIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = utteranceIdForChunk(index)
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            val result = engine.speak(chunk, mode, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                Log.w(TAG, "TTS speak returned ERROR for chunk=$index")
                notifyTtsError("朗读失败，请检查系统文字转语音设置与媒体音量。")
                finishPlayback()
                return
            }
        }
    }

    private fun finishPlayback() {
        abandonAudioFocus()
        stopPlayback(removeNotification = true)
    }

    private fun pausePlayback() {
        if (isPaused) return
        tts?.stop()
        isPaused = true
        abandonAudioFocus()
        ttsController?.onPlaybackPaused(currentMessageId)
        updatePlaybackState(PlaybackState.STATE_PAUSED)
        updateForegroundNotification(isPlaying = false)
    }

    private fun resumePlayback() {
        if (!isPaused || utteranceChunks.isEmpty()) return
        isPaused = false
        enqueueChunksFrom(activeChunkIndex)
    }

    private fun stopPlayback(removeNotification: Boolean) {
        tts?.stop()
        isPaused = false
        currentText = null
        currentMessageId = null
        utteranceChunks = emptyList()
        activeChunkIndex = 0
        abandonAudioFocus()
        ttsController?.onPlaybackStopped()
        updatePlaybackState(PlaybackState.STATE_STOPPED)
        if (removeNotification) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (isPaused) resumePlayback()
                }

                override fun onPause() {
                    pausePlayback()
                }

                override fun onStop() {
                    stopPlayback(removeNotification = true)
                }
            })
            isActive = true
        }
    }

    private fun updatePlaybackState(state: Int) {
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP
                )
                .setState(state, 0L, 1f)
                .build()
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI reply playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Text-to-speech playback controls"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TtsService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TtsService::class.java).apply { action = ACTION_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, TtsService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reading AI reply")
            .setContentText(if (isPlaying) "Playing" else "Paused")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (isPlaying) {
                    NotificationCompat.Action(
                        android.R.drawable.ic_media_pause,
                        "Pause",
                        pauseIntent
                    )
                } else {
                    NotificationCompat.Action(
                        android.R.drawable.ic_media_play,
                        "Play",
                        playIntent
                    )
                }
            )
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopIntent
                )
            )
            .build()
    }

    private fun updateForegroundNotification(isPlaying: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(isPlaying))
    }

    companion object {
        private const val TAG = "TtsService"
        const val ACTION_SPEAK = "ai.opencode.client.tts.SPEAK"
        const val ACTION_STOP = "ai.opencode.client.tts.STOP"
        const val ACTION_PAUSE = "ai.opencode.client.tts.PAUSE"
        const val ACTION_RESUME = "ai.opencode.client.tts.RESUME"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 9001
        private const val DEFAULT_MAX_SPEECH_INPUT_LENGTH = 4000

        private fun utteranceIdForChunk(index: Int) = "tts_chunk_$index"

        private fun parseChunkIndex(utteranceId: String?): Int? {
            if (utteranceId == null || !utteranceId.startsWith("tts_chunk_")) return null
            return utteranceId.removePrefix("tts_chunk_").toIntOrNull()
        }
    }
}
