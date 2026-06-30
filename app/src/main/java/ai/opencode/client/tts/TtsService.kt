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
import android.os.PowerManager
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
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var utteranceChunks: List<String> = emptyList()
    private var activeChunkIndex: Int = 0
    private var isPlaybackActive: Boolean = false
    private var chunkRetryCount: Int = 0
    private var resumeChunkAfterReinit: Int? = null
    private var chunkWatchdogRunnable: Runnable? = null
    private var progressTickerRunnable: Runnable? = null
    private var speechRate: Float = 1f
    private var pendingSpeechRate: Float = 1f
    private var chunkStartedAtMs: Long = 0L
    private var chunkEstimatedMs: Long = 0L

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (!isPaused && isPlaybackActive) pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!isPaused && isPlaybackActive) pausePlayback()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (isPaused && utteranceChunks.isNotEmpty()) resumePlayback()
            }
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            isPaused = false
            parseChunkIndex(utteranceId)?.let { activeChunkIndex = it }
            chunkRetryCount = 0
            chunkStartedAtMs = System.currentTimeMillis()
            publishProgress(isPlaying = true, paused = false)
            startProgressTicker()
            ttsController?.onPlaybackStarted(currentMessageId)
            updatePlaybackState(PlaybackState.STATE_PLAYING)
            updateForegroundNotification(isPlaying = true)
        }

        override fun onDone(utteranceId: String?) {
            cancelChunkWatchdog()
            chunkRetryCount = 0
            val chunkIndex = parseChunkIndex(utteranceId) ?: activeChunkIndex
            if (utteranceId == null) {
                Log.d(TAG, "onDone without utteranceId; using activeChunkIndex=$chunkIndex")
            }
            if (chunkIndex >= utteranceChunks.lastIndex) {
                publishProgress(progress = 1f, isPlaying = true, paused = false)
                finishPlayback()
            } else {
                publishProgress(
                    progress = (chunkIndex + 1).toFloat() / utteranceChunks.size.coerceAtLeast(1),
                    isPlaying = true,
                    paused = false,
                )
                speakChunkAt(chunkIndex + 1)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            handleUtteranceError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            handleUtteranceError(utteranceId, errorCode)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ensureForeground(isPlaying = false)

        ttsController = EntryPointAccessors.fromApplication(
            applicationContext,
            TtsServiceEntryPoint::class.java
        ).ttsController()

        setupMediaSession()
        initializeTtsEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground(isPlaying = isPlaybackActive)
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
                val rate = payload?.speechRate
                    ?: intent.getFloatExtra(EXTRA_SPEECH_RATE, speechRate)
                speechRate = rate.coerceIn(0.5f, 2.5f)
                pendingSpeechRate = speechRate
                isPaused = false
                cancelChunkWatchdog()
                chunkRetryCount = 0
                resumeChunkAfterReinit = null
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
            ACTION_SEEK -> {
                val progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                seekToProgress(progress)
            }
            ACTION_SET_SPEED -> {
                val rate = intent.getFloatExtra(EXTRA_SPEECH_RATE, speechRate)
                applySpeechRate(rate)
            }
        }
        return if (isPlaybackActive) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cancelChunkWatchdog()
        stopProgressTicker()
        releasePlaybackWakeLock()
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        shutdownTtsEngine()
        super.onDestroy()
    }

    private fun initializeTtsEngine() {
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
                if (isPlaybackActive && resumeChunkAfterReinit != null) {
                    notifyTtsError("TTS 引擎重连失败，朗读已停止。")
                } else {
                    notifyTtsError("TTS 引擎初始化失败，请检查系统文字转语音设置。")
                }
                handleTtsInitFailure()
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val locale = TtsEngineResolver.configureLanguage(engine)
            if (locale == null) {
                Log.w(TAG, "No supported TTS language for engine=$selectedEngine")
                notifyTtsError("未找到可用语音包。请到 设置 → 无障碍 → 文字转语音输出 下载中文语音。")
                handleTtsInitFailure()
                return@TextToSpeech
            }
            engine.setOnUtteranceProgressListener(utteranceListener)
            applySpeechRateToEngine(engine)
            Log.i(TAG, "TTS ready engine=$selectedEngine locale=$locale maxChunk=${effectiveMaxChunkLength()} rate=$speechRate")
            isTtsReady = true

            val resumeAt = resumeChunkAfterReinit
            if (resumeAt != null && isPlaybackActive) {
                resumeChunkAfterReinit = null
                chunkRetryCount = 0
                Log.i(TAG, "Resuming playback after engine reinit at chunk=$resumeAt")
                speakChunkAt(resumeAt)
                return@TextToSpeech
            }

            val text = pendingText
            if (text != null) {
                speakInternal(text, pendingMessageId)
                pendingText = null
                pendingMessageId = null
            }
        }, selectedEngine)
    }

    private fun shutdownTtsEngine() {
        isTtsReady = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun reinitializeTtsEngine(resumeAtChunk: Int) {
        Log.i(TAG, "Reinitializing TTS engine at chunk=$resumeAtChunk")
        resumeChunkAfterReinit = resumeAtChunk
        isTtsReady = false
        shutdownTtsEngine()
        initializeTtsEngine()
    }

    private fun handleUtteranceError(utteranceId: String?, errorCode: Int) {
        cancelChunkWatchdog()
        val chunkIndex = parseChunkIndex(utteranceId) ?: activeChunkIndex
        Log.w(
            TAG,
            "TTS utterance error chunk=$chunkIndex utteranceId=$utteranceId code=$errorCode retries=$chunkRetryCount"
        )

        if (isRetryableError(errorCode) && chunkRetryCount < MAX_CHUNK_RETRIES) {
            chunkRetryCount++
            val delayMs = 400L * chunkRetryCount
            Log.i(TAG, "Retrying chunk=$chunkIndex in ${delayMs}ms (attempt $chunkRetryCount)")
            mainHandler.postDelayed({
                if (isPlaybackActive && !isPaused && activeChunkIndex == chunkIndex) {
                    speakChunkAt(chunkIndex)
                }
            }, delayMs)
            return
        }

        if (isRetryableError(errorCode) && chunkRetryCount == MAX_CHUNK_RETRIES) {
            chunkRetryCount++
            reinitializeTtsEngine(chunkIndex)
            return
        }

        chunkRetryCount = 0
        if (chunkIndex >= utteranceChunks.lastIndex) {
            finishPlayback()
        } else {
            Log.w(TAG, "Skipping failed chunk=$chunkIndex, advancing")
            speakChunkAt(chunkIndex + 1)
        }
    }

    private fun isRetryableError(errorCode: Int): Boolean {
        return errorCode == TextToSpeech.ERROR ||
            errorCode == TextToSpeech.ERROR_NETWORK ||
            errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT ||
            errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET ||
            errorCode == TextToSpeech.ERROR_OUTPUT ||
            errorCode == TextToSpeech.ERROR_SERVICE
    }

    private fun notifyTtsError(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleTtsInitFailure() {
        pendingText = null
        pendingMessageId = null
        resumeChunkAfterReinit = null
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

    private fun acquirePlaybackWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$TAG::playback"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releasePlaybackWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun requestPlaybackFocus(): Boolean {
        val audioManager = getSystemService(AudioManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
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
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun effectiveMaxChunkLength(): Int {
        return when {
            selectedEngine.orEmpty().contains("oplus", ignoreCase = true) -> OPLUS_MAX_CHUNK_LENGTH
            selectedEngine.orEmpty().contains("coloros", ignoreCase = true) -> OPLUS_MAX_CHUNK_LENGTH
            selectedEngine.orEmpty().contains("heytap", ignoreCase = true) -> OPLUS_MAX_CHUNK_LENGTH
            else -> DEFAULT_MAX_SPEECH_INPUT_LENGTH
        }
    }

    private fun speakInternal(text: String, messageId: String?) {
        currentText = text
        currentMessageId = messageId
        utteranceChunks = TtsTextChunker.chunk(text, effectiveMaxChunkLength())
        activeChunkIndex = 0
        isPlaybackActive = true
        applySpeechRateToEngine(tts)
        if (!requestPlaybackFocus()) {
            Log.w(TAG, "Audio focus not granted; attempting speak anyway")
        }
        Log.i(
            TAG,
            "speak len=${text.length} chunks=${utteranceChunks.size} maxChunk=${effectiveMaxChunkLength()} engine=$selectedEngine rate=$speechRate"
        )
        publishProgress(progress = 0f, isPlaying = true, paused = false)
        speakChunkAt(0)
    }

    private fun seekToProgress(progress: Float) {
        if (!isPlaybackActive || utteranceChunks.isEmpty()) return
        val clamped = progress.coerceIn(0f, 1f)
        val target = (clamped * utteranceChunks.size)
            .toInt()
            .coerceIn(0, utteranceChunks.lastIndex)
        chunkRetryCount = 0
        cancelChunkWatchdog()
        isPaused = false
        publishProgress(progress = clamped, isPlaying = true, paused = false)
        speakChunkAt(target)
    }

    private fun applySpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.5f)
        pendingSpeechRate = speechRate
        applySpeechRateToEngine(tts)
        ttsController?.onSpeechRateChanged(speechRate)
        if (isPlaybackActive && !isPaused) {
            chunkEstimatedMs = estimateChunkDurationMs(utteranceChunks.getOrNull(activeChunkIndex)?.length ?: 0)
            publishProgress(isPlaying = true, paused = false)
        }
    }

    private fun applySpeechRateToEngine(engine: TextToSpeech?) {
        engine?.setSpeechRate(speechRate)
    }

    private fun estimateChunkDurationMs(chunkLength: Int): Long {
        val perCharMs = 130f / speechRate.coerceAtLeast(0.5f)
        return (chunkLength * perCharMs).toLong().coerceIn(MIN_WATCHDOG_MS / 4, MAX_WATCHDOG_MS)
    }

    private fun publishProgress(
        progress: Float? = null,
        isPlaying: Boolean = isPlaybackActive && !this.isPaused,
        paused: Boolean = this.isPaused,
    ) {
        val total = utteranceChunks.size
        if (total <= 0) return
        val computed = progress ?: run {
            val chunkBase = activeChunkIndex.toFloat() / total
            if (chunkEstimatedMs <= 0L) {
                chunkBase
            } else {
                val elapsed = (System.currentTimeMillis() - chunkStartedAtMs).coerceAtLeast(0L)
                val intra = (elapsed.toFloat() / chunkEstimatedMs).coerceIn(0f, 1f)
                chunkBase + intra / total
            }
        }.coerceIn(0f, 1f)
        ttsController?.onPlaybackProgress(
            messageId = currentMessageId,
            chunkIndex = activeChunkIndex,
            totalChunks = total,
            progress = computed,
            isPlaying = isPlaying,
            isPaused = paused,
        )
    }

    private fun startProgressTicker() {
        stopProgressTicker()
        val ticker = object : Runnable {
            override fun run() {
                if (!isPlaybackActive || isPaused) return
                publishProgress(isPlaying = true, paused = false)
                mainHandler.postDelayed(this, PROGRESS_TICK_MS)
            }
        }
        progressTickerRunnable = ticker
        mainHandler.postDelayed(ticker, PROGRESS_TICK_MS)
    }

    private fun stopProgressTicker() {
        progressTickerRunnable?.let { mainHandler.removeCallbacks(it) }
        progressTickerRunnable = null
    }

    private fun speakChunkAt(index: Int) {
        val engine = tts
        if (engine == null || !isTtsReady) {
            if (isPlaybackActive) {
                reinitializeTtsEngine(index)
            } else {
                finishPlayback()
            }
            return
        }
        if (index >= utteranceChunks.size) {
            finishPlayback()
            return
        }
        activeChunkIndex = index
        acquirePlaybackWakeLock()
        ensureForeground(isPlaying = true)
        val chunk = utteranceChunks[index]
        chunkStartedAtMs = System.currentTimeMillis()
        chunkEstimatedMs = estimateChunkDurationMs(chunk.length)
        val utteranceId = utteranceIdForChunk(index)
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val result = engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        scheduleChunkWatchdog(index, chunk.length)
        Log.d(TAG, "speak chunk=$index/${utteranceChunks.lastIndex} len=${chunk.length} result=$result")
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "TTS speak returned ERROR for chunk=$index")
            handleUtteranceError(utteranceId, TextToSpeech.ERROR)
        }
    }

    private fun scheduleChunkWatchdog(index: Int, chunkLength: Int) {
        cancelChunkWatchdog()
        val estimatedMs = (chunkLength * 130L).coerceIn(MIN_WATCHDOG_MS, MAX_WATCHDOG_MS)
        val watchdog = Runnable {
            if (!isPlaybackActive || isPaused || activeChunkIndex != index) return@Runnable
            Log.w(TAG, "Watchdog timeout for chunk=$index after ${estimatedMs}ms; advancing")
            cancelChunkWatchdog()
            chunkRetryCount = 0
            if (index >= utteranceChunks.lastIndex) {
                finishPlayback()
            } else {
                speakChunkAt(index + 1)
            }
        }
        chunkWatchdogRunnable = watchdog
        mainHandler.postDelayed(watchdog, estimatedMs)
    }

    private fun cancelChunkWatchdog() {
        chunkWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        chunkWatchdogRunnable = null
    }

    private fun finishPlayback() {
        cancelChunkWatchdog()
        stopProgressTicker()
        isPlaybackActive = false
        resumeChunkAfterReinit = null
        chunkRetryCount = 0
        releasePlaybackWakeLock()
        abandonAudioFocus()
        stopPlayback(removeNotification = true)
    }

    private fun pausePlayback() {
        if (isPaused) return
        cancelChunkWatchdog()
        stopProgressTicker()
        tts?.stop()
        isPaused = true
        releasePlaybackWakeLock()
        ttsController?.onPlaybackPaused(currentMessageId)
        publishProgress(isPlaying = false, paused = true)
        updatePlaybackState(PlaybackState.STATE_PAUSED)
        updateForegroundNotification(isPlaying = false)
    }

    private fun resumePlayback() {
        if (!isPaused || utteranceChunks.isEmpty()) return
        isPaused = false
        if (!requestPlaybackFocus()) {
            Log.w(TAG, "Audio focus not granted on resume; attempting speak anyway")
        }
        speakChunkAt(activeChunkIndex)
    }

    private fun stopPlayback(removeNotification: Boolean) {
        cancelChunkWatchdog()
        stopProgressTicker()
        tts?.stop()
        isPaused = false
        isPlaybackActive = false
        currentText = null
        currentMessageId = null
        utteranceChunks = emptyList()
        activeChunkIndex = 0
        resumeChunkAfterReinit = null
        chunkRetryCount = 0
        releasePlaybackWakeLock()
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
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Text-to-speech playback while screen is off"
            setShowBadge(false)
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
            .setContentText(
                when {
                    isPlaying && utteranceChunks.size > 1 ->
                        "Playing ${activeChunkIndex + 1}/${utteranceChunks.size}"
                    isPlaying -> "Playing"
                    else -> "Paused"
                }
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
        const val ACTION_SEEK = "ai.opencode.client.tts.SEEK"
        const val ACTION_SET_SPEED = "ai.opencode.client.tts.SET_SPEED"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SPEECH_RATE = "extra_speech_rate"
        private const val CHANNEL_ID = "tts_playback_v2"
        private const val NOTIFICATION_ID = 9001
        private const val DEFAULT_MAX_SPEECH_INPUT_LENGTH = 3500
        private const val OPLUS_MAX_CHUNK_LENGTH = 1800
        private const val MAX_CHUNK_RETRIES = 2
        private const val MIN_WATCHDOG_MS = 20_000L
        private const val MAX_WATCHDOG_MS = 180_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
        private const val PROGRESS_TICK_MS = 500L

        private fun utteranceIdForChunk(index: Int) = "tts_chunk_$index"

        private fun parseChunkIndex(utteranceId: String?): Int? {
            if (utteranceId == null || !utteranceId.startsWith("tts_chunk_")) return null
            return utteranceId.removePrefix("tts_chunk_").toIntOrNull()
        }
    }
}
