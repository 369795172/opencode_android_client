package ai.opencode.client.ui

import ai.opencode.client.data.model.ConfigProvider
import ai.opencode.client.data.model.ProviderModel
import ai.opencode.client.data.model.ProvidersResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAvailabilityTest {

    private val samplePresets = listOf(
        AppState.ModelOption("ActiveOk", "openai", "gpt-5.4"),
        AppState.ModelOption("BetaDrop", "openrouter", "anthropic/claude-opus-4.6"),
        AppState.ModelOption("Missing", "openrouter", "missing/model")
    )

    @Test
    fun `resolveAvailableModels returns presets when providers null`() {
        assertEquals(ModelPresets.list, resolveAvailableModels(ModelPresets.list, null))
    }

    @Test
    fun `resolveAvailableModels returns presets when provider list empty`() {
        val empty = ProvidersResponse(providers = emptyList())
        assertEquals(samplePresets, resolveAvailableModels(samplePresets, empty))
    }

    @Test
    fun `resolveAvailableModels keeps active and drops beta and missing`() {
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf(
                        "gpt-5.4" to ProviderModel(id = "gpt-5.4", status = "active")
                    )
                ),
                ConfigProvider(
                    id = "openrouter",
                    models = mapOf(
                        "anthropic/claude-opus-4.6" to ProviderModel(
                            id = "anthropic/claude-opus-4.6",
                            status = "beta"
                        )
                    )
                )
            )
        )
        val out = resolveAvailableModels(samplePresets, providers)
        assertEquals(1, out.count { it.modelId == "gpt-5.4" })
        assertFalse(out.any { it.modelId == "anthropic/claude-opus-4.6" })
    }

    @Test
    fun `resolveAvailableModels does not append discovered selectable models`() {
        val presets = listOf(
            AppState.ModelOption("Preset", "openai", "gpt-5.4")
        )
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf(
                        "gpt-5.4" to ProviderModel(id = "gpt-5.4", status = "active", name = "GPT-5.4"),
                        "gpt-5.3-codex" to ProviderModel(id = "gpt-5.3-codex", status = "active", name = "GPT-5.3 Codex")
                    )
                )
            )
        )
        val out = resolveAvailableModels(presets, providers)
        assertEquals(1, out.size)
        assertEquals("gpt-5.4", out[0].modelId)
        assertFalse(out.any { it.modelId == "gpt-5.3-codex" })
    }

    @Test
    fun `resolveAvailableModels falls back when filter would be empty`() {
        val presets = listOf(
            AppState.ModelOption("OnlyBeta", "openai", "x")
        )
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf("x" to ProviderModel(id = "x", status = "beta"))
                )
            )
        )
        assertEquals(presets, resolveAvailableModels(presets, providers))
    }

    @Test
    fun `resolveAvailableModels treats null status as selectable`() {
        val presets = listOf(AppState.ModelOption("Legacy", "zai", "glm-5"))
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "zai",
                    models = mapOf("glm-5" to ProviderModel(id = "glm-5", status = null))
                )
            )
        )
        assertEquals(presets, resolveAvailableModels(presets, providers))
    }

    @Test
    fun `remapSelectedModelIndex preserves selection when still present`() {
        val old = listOf(
            AppState.ModelOption("A", "p", "1"),
            AppState.ModelOption("B", "p", "2")
        )
        val newList = listOf(
            AppState.ModelOption("B", "p", "2"),
            AppState.ModelOption("A", "p", "1")
        )
        assertEquals(0, remapSelectedModelIndex(old, newList, 1))
    }

    @Test
    fun `remapSelectedModelIndex returns zero when previous option gone`() {
        val old = listOf(
            AppState.ModelOption("A", "p", "1"),
            AppState.ModelOption("B", "p", "2")
        )
        val newList = listOf(AppState.ModelOption("C", "p", "3"))
        assertEquals(0, remapSelectedModelIndex(old, newList, 1))
    }

    @Test
    fun `isProviderModelSelectable accepts active and null status`() {
        assertTrue(isProviderModelSelectable(ProviderModel(status = null)))
        assertTrue(isProviderModelSelectable(ProviderModel(status = "active")))
        assertFalse(isProviderModelSelectable(ProviderModel(status = "beta")))
    }

}
