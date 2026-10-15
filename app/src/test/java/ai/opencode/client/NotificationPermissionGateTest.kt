package ai.opencode.client

import ai.opencode.client.notifications.NotificationPermissionGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionGateTest {

    @Test
    fun `fresh android 13 device with notifications off prompts once`() {
        assertTrue(
            NotificationPermissionGate.shouldRequestOnce(
                hasPromptedV1 = false,
                notificationsEnabled = false,
                runtimePermissionSupported = true,
            )
        )
    }

    @Test
    fun `denied prompt is never repeated after the v1 marker`() {
        assertFalse(
            NotificationPermissionGate.shouldRequestOnce(
                hasPromptedV1 = true,
                notificationsEnabled = false,
                runtimePermissionSupported = true,
            )
        )
    }

    @Test
    fun `already granted never prompts`() {
        assertFalse(
            NotificationPermissionGate.shouldRequestOnce(
                hasPromptedV1 = false,
                notificationsEnabled = true,
                runtimePermissionSupported = true,
            )
        )
    }

    @Test
    fun `below android 13 never prompts at runtime`() {
        assertFalse(
            NotificationPermissionGate.shouldRequestOnce(
                hasPromptedV1 = false,
                notificationsEnabled = false,
                runtimePermissionSupported = false,
            )
        )
    }

    @Test
    fun `prompted and granted stays silent`() {
        assertFalse(
            NotificationPermissionGate.shouldRequestOnce(
                hasPromptedV1 = true,
                notificationsEnabled = true,
                runtimePermissionSupported = true,
            )
        )
    }
}
