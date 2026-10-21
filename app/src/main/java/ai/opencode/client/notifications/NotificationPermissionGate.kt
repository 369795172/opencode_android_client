package ai.opencode.client.notifications

/**
 * Pure decision table for the POST_NOTIFICATIONS runtime-permission prompt.
 *
 * The app asks exactly once (the v1 marker is persisted at request time via
 * SettingsManager) after the first successful OpenCode server connection. A
 * denial never re-prompts; the caller shows a one-shot snackbar pointing at
 * system settings instead. There is no in-app settings screen for this.
 */
object NotificationPermissionGate {

    /**
     * Whether the one-time prompt should fire now.
     *
     * [runtimePermissionSupported] is false below Android 13, where
     * POST_NOTIFICATIONS is granted at install time and must not be prompted
     * for. [notificationsEnabled] is the current NotificationManagerCompat
     * state; an already-granted (or pre-13 auto-granted) device never asks.
     */
    fun shouldRequestOnce(
        hasPromptedV1: Boolean,
        notificationsEnabled: Boolean,
        runtimePermissionSupported: Boolean,
    ): Boolean = runtimePermissionSupported && !hasPromptedV1 && !notificationsEnabled
}
