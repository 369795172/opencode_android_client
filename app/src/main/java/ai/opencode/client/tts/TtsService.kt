package ai.opencode.client.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.opencode.client.MainActivity
import ai.opencode.client.R
import ai.opencode.client.di.TtsServiceEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.util.Locale

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Promote to foreground before TTS engine init (OEM engines can block >5s).
        ensureForeground(isPlaying = false)

        ttsController = EntryPointAccessors.fromApplication(
            applicationContext,
            TtsServiceEntryPoint::class.java
        ).ttsController()

        setupMediaSession()

        tts = TextToSpeech(applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech init failed with status=$status")
                handleTtsInitFailure()
                return@TextToSpeech
            }
            if (!configureTtsLanguage()) {
                Log.w(TAG, "No supported TTS language found")
                handleTtsInitFailure()
                return@TextToSpeech
            }
            isTtsReady = true
            val text = pendingText
            if (text != null) {
                speakInternal(text, pendingMessageId)
                pendingText = null
                pendingMessageId = null
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isPaused = false
                ttsController?.onPlaybackStarted(currentMessageId)
                updatePlaybackState(PlaybackState.STATE_PLAYING)
                updateForegroundNotification(isPlaying = true)
            }

            override fun onDone(utteranceId: String?) {
                stopPlayback(removeNotification = true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.w(TAG, "TTS utterance error (legacy): utteranceId=$utteranceId")
                stopPlayback(removeNotification = true)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS utterance error: utteranceId=$utteranceId code=$errorCode")
                stopPlayback(removeNotification = true)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground(isPlaying = false)
        when (intent?.action) {
            ACTION_STOP -> stopPlayback(removeNotification = true)
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (text.isNullOrBlank()) {
                    stopPlayback(removeNotification = true)
                    return START_NOT_STICKY
                }
                val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
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
        mediaSession?.release()
        mediaSession = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun configureTtsLanguage(): Boolean {
        val engine = tts ?: return false
        val candidates = listOf(
            Locale.getDefault(),
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.US,
        )
        for (locale in candidates) {
            when (engine.setLanguage(locale)) {
                TextToSpeech.LANG_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> return true
            }
        }
        return false
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

    private fun speakInternal(text: String, messageId: String?) {
        currentText = text
        currentMessageId = messageId
        ensureForeground(isPlaying = true)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "TTS speak returned ERROR")
            stopPlayback(removeNotification = true)
        }
    }

    private fun pausePlayback() {
        if (isPaused) return
        tts?.stop()
        isPaused = true
        ttsController?.onPlaybackPaused(currentMessageId)
        updatePlaybackState(PlaybackState.STATE_PAUSED)
        updateForegroundNotification(isPlaying = false)
    }

    private fun resumePlayback() {
        val text = currentText ?: return
        if (!isPaused) return
        isPaused = false
        speakInternal(text, currentMessageId)
    }

    private fun stopPlayback(removeNotification: Boolean) {
        tts?.stop()
        isPaused = false
        currentText = null
        currentMessageId = null
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
                    if (isPaused) {
                        resumePlayback()
                    }
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
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

        return builder.build()
    }

    private fun updateForegroundNotification(isPlaying: Boolean) {
        val notification = buildNotification(isPlaying)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
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
        private const val UTTERANCE_ID = "tts_utterance"
    }
}
