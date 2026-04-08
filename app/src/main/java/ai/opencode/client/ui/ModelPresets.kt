package ai.opencode.client.ui

/**
 * Curated model presets for the model selector, matching iOS implementation.
 * Only these models are shown in the dropdown instead of the full API list.
 *
 * Claude/Gemini entries use the `openrouter` provider with full route keys (e.g. anthropic/…, google/…)
 * as returned by GET /config/providers on typical OpenCode deployments.
 */
object ModelPresets {
    /** Curated presets: merged fork defaults (GLM5, Gemini) with upstream OpenCode Android list. */
    val list: List<AppState.ModelOption> = listOf(
        AppState.ModelOption("GPT-5.3", "openai", "gpt-5.3-codex"),
        AppState.ModelOption("GPT-5.4", "openai", "gpt-5.4"),
        AppState.ModelOption("Opus", "openrouter", "anthropic/claude-opus-4.6"),
        AppState.ModelOption("Sonnet", "openrouter", "anthropic/claude-sonnet-4.6"),
        AppState.ModelOption("GLM-5-turbo", "zai-coding-plan", "glm-5-turbo"),
        AppState.ModelOption("GLM5", "zai-coding-plan", "glm-5"),
        AppState.ModelOption("Gemini Pro", "openrouter", "google/gemini-3.1-pro-preview"),
        AppState.ModelOption("Gemini Flash", "openrouter", "google/gemini-3-flash-preview"),
    )
}
