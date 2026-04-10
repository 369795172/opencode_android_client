package ai.opencode.client.ui

import ai.opencode.client.data.audio.AIBuildersAudioClient
import ai.opencode.client.data.repository.OpenCodeRepository
import ai.opencode.client.util.PersistedModelHealth
import ai.opencode.client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun applySavedSettings(
    repository: OpenCodeRepository,
    settingsManager: SettingsManager,
    state: MutableStateFlow<AppState>
) {
    repository.configure(
        baseUrl = settingsManager.serverUrl,
        username = settingsManager.username,
        password = settingsManager.password,
        workspaceDirectory = settingsManager.workspaceDirectory.ifBlank { null }
    )

    val savedModelIndex = settingsManager.selectedModelIndex
    val maxModelIdx = (state.value.availableModels.size - 1).coerceAtLeast(0)
    val clampedModelIndex = savedModelIndex.coerceIn(0, maxModelIdx)
    if (clampedModelIndex != savedModelIndex) {
        settingsManager.selectedModelIndex = clampedModelIndex
    }

    state.update {
        it.copy(
            currentSessionId = settingsManager.currentSessionId,
            selectedModelIndex = clampedModelIndex,
            selectedAgentName = settingsManager.selectedAgentName ?: "build",
            themeMode = settingsManager.themeMode,
            modelHealth = settingsManager.getModelHealthSnapshot().mapValues { (_, entry) ->
                ModelHealth(
                    healthy = entry.healthy,
                    updatedAtMs = entry.updatedAtMs,
                    reason = entry.reason
                )
            }
        )
    }

    val savedSignature = settingsManager.aiBuilderLastOKSignature
    val currentSignature = aiBuilderSignature(
        settingsManager.aiBuilderBaseURL.trim(),
        AIBuildersAudioClient.sanitizeBearerToken(settingsManager.aiBuilderToken)
    )
    if (savedSignature != null && savedSignature == currentSignature) {
        state.update { it.copy(aiBuilderConnectionOK = true) }
    }
}

internal fun launchConnectionTest(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onHealthyConnection: () -> Unit
) {
    scope.launch {
        state.update { it.copy(isConnecting = true, error = null) }
        repository.checkHealth()
            .onSuccess { health ->
                state.update {
                    it.copy(
                        isConnected = health.healthy,
                        serverVersion = health.version,
                        isConnecting = false
                    )
                }
                if (health.healthy) {
                    onHealthyConnection()
                }
            }
            .onFailure { error ->
                state.update {
                    it.copy(
                        isConnected = false,
                        isConnecting = false,
                        error = errorMessageOrFallback(error, "Connection failed")
                    )
                }
            }
    }
}

internal fun persistModelHealth(
    settingsManager: SettingsManager,
    health: Map<String, ModelHealth>
) {
    val persisted = health.mapValues { (_, value) ->
        PersistedModelHealth(
            healthy = value.healthy,
            updatedAtMs = value.updatedAtMs,
            reason = value.reason
        )
    }
    settingsManager.setModelHealthSnapshot(persisted)
}

internal fun launchAIBuilderConnectionTest(
    scope: CoroutineScope,
    settingsManager: SettingsManager,
    state: MutableStateFlow<AppState>
) {
    scope.launch {
        state.update { it.copy(isTestingAIBuilderConnection = true, aiBuilderConnectionError = null) }
        val token = AIBuildersAudioClient.sanitizeBearerToken(settingsManager.aiBuilderToken)
        if (token.isEmpty()) {
            state.update {
                it.copy(
                    isTestingAIBuilderConnection = false,
                    aiBuilderConnectionOK = false,
                    aiBuilderConnectionError = "AI Builder token is empty"
                )
            }
            return@launch
        }

        val baseURL = settingsManager.aiBuilderBaseURL.trim()
        AIBuildersAudioClient.testConnection(baseURL, token)
            .onSuccess {
                val signature = aiBuilderSignature(baseURL, token)
                settingsManager.aiBuilderLastOKSignature = signature
                settingsManager.aiBuilderLastOKTestedAt = System.currentTimeMillis()
                state.update {
                    it.copy(
                        isTestingAIBuilderConnection = false,
                        aiBuilderConnectionOK = true,
                        aiBuilderConnectionError = null
                    )
                }
            }
            .onFailure { error ->
                settingsManager.aiBuilderLastOKSignature = null
                state.update {
                    it.copy(
                        isTestingAIBuilderConnection = false,
                        aiBuilderConnectionOK = false,
                        aiBuilderConnectionError = errorMessageOrFallback(error, "Connection failed")
                    )
                }
            }
    }
}
