package ai.opencode.client

import androidx.compose.ui.unit.dp
import ai.opencode.client.ui.chat.ChatUiTuning
import ai.opencode.client.ui.chat.resolveChatActionsVerticalLayout
import ai.opencode.client.ui.chat.shouldUseVerticalChatActions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUiTuningTest {
    @Test
    fun `chat actions stay horizontal below threshold`() {
        assertFalse(shouldUseVerticalChatActions(ChatUiTuning.inputActionVerticalThreshold - 1.dp))
    }

    @Test
    fun `chat actions switch vertical at threshold`() {
        assertTrue(shouldUseVerticalChatActions(ChatUiTuning.inputActionVerticalThreshold))
        assertTrue(shouldUseVerticalChatActions(ChatUiTuning.inputActionVerticalThreshold + 8.dp))
    }

    @Test
    fun `hysteresis keeps horizontal when height is between thresholds`() {
        val inBand = ChatUiTuning.inputActionVerticalThreshold - 1.dp
        assertFalse(resolveChatActionsVerticalLayout(textFieldHeight = inBand, wasVertical = false))
    }

    @Test
    fun `hysteresis keeps vertical when height is between thresholds`() {
        val inBand = ChatUiTuning.inputActionHorizontalThreshold + 1.dp
        assertTrue(resolveChatActionsVerticalLayout(textFieldHeight = inBand, wasVertical = true))
    }

    @Test
    fun `hysteresis exits vertical below horizontal threshold`() {
        val belowExit = ChatUiTuning.inputActionHorizontalThreshold - 1.dp
        assertFalse(resolveChatActionsVerticalLayout(textFieldHeight = belowExit, wasVertical = true))
    }

    @Test
    fun `hysteresis enters vertical at vertical threshold`() {
        assertTrue(
            resolveChatActionsVerticalLayout(
                textFieldHeight = ChatUiTuning.inputActionVerticalThreshold,
                wasVertical = false
            )
        )
    }
}
