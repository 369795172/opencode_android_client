package ai.opencode.client.ui

/**
 * Curated model presets for the model selector.
 * Only these models are shown in the dropdown instead of the full API list.
 *
 * Claude entries default to a local CLI bridge provider (`claude-cli`) and Gemini routes to direct
 * Google provider (`google`) to avoid OpenRouter policy failures observed in production.
 *
 * Removed:
 * - GPT-5.3-codex: replaced by GPT-5.4 in the default preset list.
 * - GLM-5-turbo: kept GLM-5 as the stable/default GLM route.
 * - GLM5 preset switched from zai-coding-plan/glm-5 to zai/glm-5 (glm-5 was dropped from
 *   zai-coding-plan upstream, causing the preset to be filtered out).
 */
object ModelPresets {
    /** Curated presets: merged fork defaults (GLM5, Gemini) with upstream OpenCode Android list. */
    val list: List<AppState.ModelOption> = listOf(
        AppState.ModelOption("Opus", "claude-cli", "claude-opus-4.6"),
        AppState.ModelOption("Sonnet", "claude-cli", "claude-sonnet-4.6"),
        AppState.ModelOption("Haiku", "claude-cli", "claude-haiku-4.5"),
        AppState.ModelOption("GPT-5.4", "openai", "gpt-5.4"),
        AppState.ModelOption("GLM5", "zai", "glm-5"),
        AppState.ModelOption("Gemini Pro", "google", "gemini-3.1-pro-preview"),
        AppState.ModelOption("Gemini Flash", "google", "gemini-3-flash-preview"),
    )
}
