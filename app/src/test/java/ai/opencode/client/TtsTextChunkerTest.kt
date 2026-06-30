package ai.opencode.client

import ai.opencode.client.tts.TtsTextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextChunkerTest {

    @Test
    fun chunk_returnsSingleItemWhenUnderLimit() {
        val text = "Hello world"
        assertEquals(listOf(text), TtsTextChunker.chunk(text, 4000))
    }

    @Test
    fun chunk_splitsLongTextAtSentenceBoundary() {
        val sentence = "这是第一句。"
        val text = sentence.repeat(800)
        val chunks = TtsTextChunker.chunk(text, 4000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length < 4000 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun chunk_respectsSafetyMarginBelowMaxLength() {
        val text = "a".repeat(5000)
        val chunks = TtsTextChunker.chunk(text, 4000)
        assertTrue(chunks.all { it.length <= 3999 })
    }
}
