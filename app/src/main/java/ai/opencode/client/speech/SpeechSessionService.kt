package ai.opencode.client.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.opencode.client.MainActivity
import ai.opencode.client.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keep-alive foreground service for active speech input. The microphone FGS type
 * keeps mic access alive after the screen turns off (Android 11+ silences the mic
 * for backgrounded apps otherwise) and the partial wake lock keeps the CPU running
 * so realtime chunks and heartbeats keep flowing. The service owns no audio logic;
 * [ai.opencode.client.ui.MainViewModel] drives its lifecycle around the recording
 * and transcription phases.
 */
class SpeechSessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopKeepAlive()
                return START_NOT_STICKY
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        acquireWakeLock()
        isRunning.set(true)
        Log.i(TAG, "Speech keep-alive foreground service started")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        isRunning.set(false)
        Log.i(TAG, "Speech keep-alive foreground service destroyed")
        super.onDestroy()
    }

    private fun stopKeepAlive() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$TAG::speech_recording"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Speech recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps speech recognition alive while the screen is off"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音录入进行中")
            .setContentText("熄屏后录音不会中断，点击返回应用")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "SpeechSessionService"
        private const val CHANNEL_ID = "speech_recording_v1"
        private const val NOTIFICATION_ID = 9002
        private const val ACTION_START = "ai.opencode.client.speech.START"
        private const val ACTION_STOP = "ai.opencode.client.speech.STOP"
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L

        /** True while the keep-alive foreground service is up. */
        val isRunning = AtomicBoolean(false)

        /**
         * Must be called while the app is in the foreground (user tapped record);
         * Android 14 forbids starting a microphone FGS from the background.
         */
        fun start(context: Context) {
            val intent = Intent(context, SpeechSessionService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SpeechSessionService::class.java))
        }
    }
}
