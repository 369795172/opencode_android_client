package ai.opencode.client

import ai.opencode.client.tts.TtsController
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsControllerTest {

    @Test
    fun stripMarkdown_removesCommonMarkers() {
        val input = "# Title\n\nHello **world** and `code` with [link](https://example.com)."
        val result = TtsController.stripMarkdown(input)
        assertEquals("Title Hello world and code with link.", result)
    }

    @Test
    fun stripMarkdown_removesCodeBlocks() {
        val input = "Before\n```kotlin\nval x = 1\n```\nAfter"
        val result = TtsController.stripMarkdown(input)
        assertEquals("Before After", result)
    }
}
