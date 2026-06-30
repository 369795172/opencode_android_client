package ai.opencode.client

import ai.opencode.client.tts.TtsProgressMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsProgressMapperTest {

    @Test
    fun chunkIndex_mapsZeroToFirstAndOneToLast() {
        assertEquals(0, TtsProgressMapper.chunkIndexForProgress(0f, 8))
        assertEquals(7, TtsProgressMapper.chunkIndexForProgress(1f, 8))
        assertEquals(4, TtsProgressMapper.chunkIndexForProgress(0.5f, 9))
    }

    @Test
    fun chunkIndex_singleChunkAlwaysZero() {
        assertEquals(0, TtsProgressMapper.chunkIndexForProgress(0.8f, 1))
    }
}
