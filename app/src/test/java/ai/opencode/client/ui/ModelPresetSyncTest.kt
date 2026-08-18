package ai.opencode.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPresetSyncTest {

    private val sample = listOf(
        AppState.ModelOption("GLM", "zai-coding-plan", "glm-5.2", modelIdPrefix = "glm-"),
        AppState.ModelOption("GPT-5.6 Sol", "openai", "gpt-5.6-sol"),
    )

    @Test
    fun `encode then parse round-trips pinned models`() {
        val encoded = ModelPresetSync.encode(sample)
        val parsed = ModelPresetSync.parse(encoded)
        assertEquals(sample, parsed)
        assertTrue(encoded.contains("\"display_name\""))
        assertTrue(encoded.contains("\"provider_id\""))
        assertTrue(encoded.contains("\"model_id\""))
        assertTrue(encoded.contains("\"model_id_prefix\""))
    }

    @Test
    fun `parse ignores unknown keys`() {
        val json = """
            [
              {
                "display_name": "Opus",
                "provider_id": "claude-cli",
                "model_id": "claude-opus-4.6",
                "extra": true
              }
            ]
        """.trimIndent()
        val parsed = ModelPresetSync.parse(json)
        assertEquals(
            listOf(AppState.ModelOption("Opus", "claude-cli", "claude-opus-4.6")),
            parsed
        )
    }

    @Test
    fun `parse returns null for blank invalid or non-array json`() {
        assertNull(ModelPresetSync.parse(""))
        assertNull(ModelPresetSync.parse("   "))
        assertNull(ModelPresetSync.parse("{not json"))
        assertNull(ModelPresetSync.parse("{}"))
        assertNull(ModelPresetSync.parse("null"))
        assertNull(ModelPresetSync.parse("""{"models":[]}"""))
    }

    @Test
    fun `parse reads workspace file schema with models wrapper`() {
        val json = """
            {
              "schema_version": 1,
              "generated_at": "2026-08-18T00:00:00Z",
              "models": [
                {
                  "display_name": "glm-5.2",
                  "provider_id": "zai-coding-plan",
                  "model_id": "glm-5.2"
                },
                {
                  "display_name": "gpt-5-4",
                  "provider_id": "openai",
                  "model_id": "gpt-5.4"
                }
              ]
            }
        """.trimIndent()
        val parsed = ModelPresetSync.parse(json)
        assertEquals(
            listOf(
                AppState.ModelOption("glm-5.2", "zai-coding-plan", "glm-5.2"),
                AppState.ModelOption("gpt-5-4", "openai", "gpt-5.4"),
            ),
            parsed,
        )
    }

    @Test
    fun `loadOrSeed uses ModelPresets when stored json is empty`() {
        val loaded = ModelPresetSync.loadOrSeed("")
        assertEquals(ModelPresets.list, loaded.models)
        assertTrue(loaded.shouldPersist)
    }

    @Test
    fun `loadOrSeed keeps parsed list without rewriting`() {
        val encoded = ModelPresetSync.encode(sample)
        val loaded = ModelPresetSync.loadOrSeed(encoded)
        assertEquals(sample, loaded.models)
        assertEquals(false, loaded.shouldPersist)
    }

    @Test
    fun `loadOrSeed falls back to seed when stored json is invalid`() {
        val loaded = ModelPresetSync.loadOrSeed("{bad")
        assertEquals(ModelPresets.list, loaded.models)
        assertTrue(loaded.shouldPersist)
    }
}
