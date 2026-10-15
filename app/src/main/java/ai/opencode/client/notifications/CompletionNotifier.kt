package ai.opencode.client.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ai.opencode.client.MainActivity
import ai.opencode.client.R
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Caller-driven shell that posts task-completion notifications on the
 * dedicated [CHANNEL_ID] channel. It does NOT detect completion: the
 * notifier plugin (later todos) prepares a [CompletionNotificationSpec] from
 * CompletionBrief outputs and hands it over.
 *
 * Lock-screen policy: the private version shows the normalized title plus
 * the capped brief; the public version exposes ONLY the normalized title.
 */
class CompletionNotifier(private val context: Context) {

    /** Creates the channel once; safe to call on every post. */
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Task completion",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when an OpenCode task finishes"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts (or updates) the notification for [spec.sessionId]. Returns
     * false without posting when notifications are disabled for the app
     * (denied POST_NOTIFICATIONS or the master toggle is off).
     */
    fun notify(spec: CompletionNotificationSpec): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel()
        manager.notify(spec.notificationId, buildNotification(spec))
        return true
    }

    fun cancel(sessionId: String) {
        NotificationManagerCompat.from(context)
            .cancel(CompletionNotificationSpec.notificationIdFor(sessionId))
    }

    internal fun buildNotification(spec: CompletionNotificationSpec): Notification {
        // Generic content intent for now; per-session deep-link routing and
        // "Read brief"/"Read aloud" actions arrive in a later todo. The
        // per-session request code keeps PendingIntents collision-safe even
        // while the intent itself is still generic.
        val contentIntent = PendingIntent.getActivity(
            context,
            spec.contentRequestCode,
            Intent(context, MainActivity::class.java),
            PENDING_INTENT_FLAGS
        )
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(spec.publicVersionText)
            .build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(spec.title)
            .setContentText(spec.brief)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "task_completion_v1"

        /** All shell PendingIntents are immutable and update-safe. */
        const val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
