package ai.opencode.client.ui

/**
 * Curated model presets for the model selector, matching iOS implementation.
 * Only these models are shown in the dropdown instead of the full API list.
 */
object ModelPresets {
    /** Curated presets: merged fork defaults (GLM5, Gemini) with upstream OpenCode Android list. */
    val list: List<AppState.ModelOption> = listOf(
        AppState.ModelOption("GPT-5.3", "openai", "gpt-5.3-codex"),
        AppState.ModelOption("GPT-5.4", "openai", "gpt-5.4"),
        AppState.ModelOption("Opus", "anthropic", "claude-opus-4-6"),
        AppState.ModelOption("Sonnet", "anthropic", "claude-sonnet-4-6"),
        AppState.ModelOption("GLM5", "zai-coding-plan", "glm-5"),
        AppState.ModelOption("Gemini Pro", "google", "gemini-3.1-pro-preview"),
        AppState.ModelOption("Gemini Flash", "google", "gemini-3-flash-preview"),
    )
}
