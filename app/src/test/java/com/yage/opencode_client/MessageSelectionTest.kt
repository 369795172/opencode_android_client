package com.yage.opencode_client

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies that user and AI messages use SelectionContainer for long-press copy.
 * ChatScreen.kt TextPart (user + AI) and ReasoningCard should wrap content in SelectionContainer.
 */
class MessageSelectionTest {

    @Test
    fun `ChatScreen uses SelectionContainer for message copy`() {
        val chatScreen = (
            File("app/src/main/java/com/yage/opencode_client/ui/chat/ChatScreen.kt").takeIf { it.exists() }
                ?: File("src/main/java/com/yage/opencode_client/ui/chat/ChatScreen.kt")
        ).readText()
        assertTrue(
            "ChatScreen must use SelectionContainer for user/AI message copy",
            chatScreen.contains("SelectionContainer")
        )
    }
}
