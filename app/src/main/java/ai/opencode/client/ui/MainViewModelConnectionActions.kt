package ai.opencode.client.ui

import ai.opencode.client.data.repository.OpenCodeRepository
import ai.opencode.client.data.repository.HostProfileStore
import ai.opencode.client.util.SettingsManager
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun persistSelectedModel(
    settingsManager: SettingsManager,
    option: AppState.ModelOption?,
    index: Int
) {
    if (option != null) {
        settingsManager.selectedModelProviderId = option.providerId
        settingsManager.selectedModelId = option.modelId
    }
    settingsManager.selectedModelIndex = index
}

internal fun applySavedSettings(
    repository: OpenCodeRepository,
    settingsManager: SettingsManager,
    hostProfileStore: HostProfileStore,
    state: MutableStateFlow<AppState>
) {
    settingsManager.migrateRemovedGpt56SolProModelIndices()
    val currentProfile = hostProfileStore.currentProfile()
    val password = currentProfile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
    repository.configure(
        baseUrl = currentProfile.serverUrl,
        username = currentProfile.basicAuth?.username,
        password = password
    )

    val loaded = ModelPresetSync.loadOrSeed(settingsManager.pinnedModels)
    if (loaded.shouldPersist) {
        settingsManager.pinnedModels = ModelPresetSync.encode(loaded.models)
    }
    val models = resolveAvailableModels(loaded.models, null)
    val selectedIndex = resolvePersistedModelIndex(
        models = models,
        providerId = settingsManager.selectedModelProviderId,
        modelId = settingsManager.selectedModelId,
        legacyIndex = settingsManager.selectedModelIndex,
    )
    persistSelectedModel(settingsManager, models.getOrNull(selectedIndex), selectedIndex)

    state.update {
        it.copy(
            currentSessionId = settingsManager.currentSessionId,
            hostProfiles = hostProfileStore.profiles(),
            currentHostProfileId = currentProfile.id,
            pinnedModels = loaded.models,
            selectedModelIndex = selectedIndex,
            selectedAgentName = settingsManager.selectedAgentName ?: "build",
            themeMode = settingsManager.themeMode,
            languageMode = settingsManager.languageMode
        )
    }

    val savedSignature = settingsManager.aiBuilderLastOKSignature
    val currentSignature = aiBuilderSignature(
        settingsManager.aiBuilderBaseURL.trim(),
        sanitizeBearerToken(settingsManager.aiBuilderToken)
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

internal fun launchAIBuilderConnectionTest(
    scope: CoroutineScope,
    settingsManager: SettingsManager,
    voiceFlowClient: VoiceFlowClient,
    state: MutableStateFlow<AppState>
) {
    scope.launch {
        state.update { it.copy(isTestingAIBuilderConnection = true, aiBuilderConnectionError = null) }
        val token = sanitizeBearerToken(settingsManager.aiBuilderToken)
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
        // Refresh the library config with the current endpoint before probing so the
        // reachability check hits the same backend the realtime session will use.
        voiceFlowClient.updateConfig(
            VoiceFlowConfig(
                endpoint = baseURL.ifEmpty { VoiceFlowConfig.DEFAULT_ENDPOINT },
                tokenProvider = { token },
            )
        )
        runCatching { voiceFlowClient.testConnection() }
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
