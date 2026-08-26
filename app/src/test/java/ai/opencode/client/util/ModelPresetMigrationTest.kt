package ai.opencode.client.util

import ai.opencode.client.ui.AppState
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelPresetMigrationTest {

    @Test
    fun `upgradeStalePinnedModel upgrades zai GLM 4x to GLM 5_3`() {
        val stale = AppState.ModelOption("GLM-4.7", "zai-coding-plan", "glm-4.7", modelIdPrefix = "glm-4")
        val upgraded = upgradeStalePinnedModel(stale)
        assertEquals("GLM-5.3", upgraded.displayName)
        assertEquals("glm-5.3", upgraded.modelId)
        assertEquals("glm-5", upgraded.modelIdPrefix)
        assertEquals("zai-coding-plan", upgraded.providerId)
    }

    @Test
    fun `upgradeStalePinnedModel upgrades gemini 3_6 to 3_7 flash`() {
        val stale = AppState.ModelOption("Gemini 3.6 Flash", "google", "gemini-3.6-flash")
        val upgraded = upgradeStalePinnedModel(stale)
        assertEquals("Gemini 3.7 Flash", upgraded.displayName)
        assertEquals("gemini-3.7-flash", upgraded.modelId)
    }

    @Test
    fun `upgradeStalePinnedModel keeps current entries untouched`() {
        val current = AppState.ModelOption("GLM-5.3", "zai-coding-plan", "glm-5.3", modelIdPrefix = "glm-5")
        assertEquals(current, upgradeStalePinnedModel(current))
        val other = AppState.ModelOption("Opus", "claude-cli", "claude-opus-4.6")
        assertEquals(other, upgradeStalePinnedModel(other))
    }
}
