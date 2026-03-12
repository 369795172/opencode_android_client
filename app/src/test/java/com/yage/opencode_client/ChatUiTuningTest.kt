package com.yage.opencode_client

import androidx.compose.ui.unit.dp
import com.yage.opencode_client.ui.chat.ChatUiTuning
import com.yage.opencode_client.ui.chat.shouldUseVerticalChatActions
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
}
