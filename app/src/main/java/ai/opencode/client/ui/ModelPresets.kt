package ai.opencode.client.ui

/**
 * First-launch seed for the pinned model list.
 * Runtime selection uses SettingsManager.pinnedModels (JSON), not this constant.
 */
object ModelPresets {
    val list: List<AppState.ModelOption> = listOf(
        AppState.ModelOption("GLM-5.3", "zai-coding-plan", "glm-5.3", modelIdPrefix = "glm-5"),
        AppState.ModelOption("GPT-5.6 Sol", "openai", "gpt-5.6-sol"),
        AppState.ModelOption("Gemini 3.7 Flash", "google", "gemini-3.7-flash"),
        AppState.ModelOption("DeepSeek Local", "ds4", "deepseek-v4-flash"),
        AppState.ModelOption("DeepSeek V4 Pro", "deepseek", "deepseek-v4-pro"),
        AppState.ModelOption("Ollama GLM 5.2", "ollama-cloud", "glm-5.2"),
        AppState.ModelOption("GPT-5.6 Sol Fast", "openai", "gpt-5.6-sol-fast"),
        AppState.ModelOption("GPT-5.6 Terra Fast", "openai", "gpt-5.6-terra-fast"),
        AppState.ModelOption("GPT-5.6 Luna", "openai", "gpt-5.6-luna"),
        AppState.ModelOption("Grok 4.6", "xai", "grok-4.6"),
        AppState.ModelOption("Qwen 3.8 27B", "qwen38", "qwen3.8-27b"),
        AppState.ModelOption("Opus", "claude-cli", "claude-opus-4.6"),
        AppState.ModelOption("Sonnet", "claude-cli", "claude-sonnet-4.6"),
        AppState.ModelOption("Haiku", "claude-cli", "claude-haiku-4.5"),
    )
}
