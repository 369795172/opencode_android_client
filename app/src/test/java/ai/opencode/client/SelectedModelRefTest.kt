package ai.opencode.client

import ai.opencode.client.ui.ModelPresets
import ai.opencode.client.util.SelectedModelRef
import ai.opencode.client.util.parseSessionModelRef
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedModelRefTest {

    @Test
    fun `parseSessionModelRef migrates legacy index to preset identity`() {
        val preset = ModelPresets.list[1]
        assertEquals(
            SelectedModelRef(preset.providerId, preset.modelId),
            parseSessionModelRef("1")
        )
    }

    @Test
    fun `parseSessionModelRef reads json identity`() {
        val ref = SelectedModelRef("openai", "gpt-5.6-sol")
        assertEquals(ref, parseSessionModelRef(Json.encodeToString(ref)))
    }

    @Test
    fun `parseSessionModelRef reads tab-separated identity`() {
        assertEquals(
            SelectedModelRef("openai", "gpt-5.6-sol"),
            parseSessionModelRef("openai\tgpt-5.6-sol")
        )
    }

    @Test
    fun `parseSessionModelRef returns null for garbage`() {
        assertNull(parseSessionModelRef("not-a-ref"))
        assertNull(parseSessionModelRef(""))
    }
}
