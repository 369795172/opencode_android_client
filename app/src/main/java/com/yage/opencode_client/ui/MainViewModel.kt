package com.yage.opencode_client.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yage.opencode_client.data.audio.AIBuildersAudioClient
import com.yage.opencode_client.data.audio.AudioRecorderManager
import com.yage.opencode_client.data.model.*
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionFormSettings(
    val serverUrl: String,
    val username: String,
    val password: String
)

data class AIBuilderSettings(
    val baseURL: String,
    val token: String,
    val customPrompt: String,
    val terminology: String
)

data class AppState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val sessions: List<Session> = emptyList(),
    val expandedSessionIds: Set<String> = emptySet(),
    val currentSessionId: String? = null,
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val messages: List<MessageWithParts> = emptyList(),
    val messageLimit: Int = 30,
    val isLoadingMessages: Boolean = false,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgentName: String = "build",
    val selectedModelIndex: Int = 0,
    val providers: ProvidersResponse? = null,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val inputText: String = "",
    val error: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val filePathToShowInFiles: String? = null,
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val speechError: String? = null,
    val aiBuilderConnectionOK: Boolean = false,
    val aiBuilderConnectionError: String? = null,
    val isTestingAIBuilderConnection: Boolean = false
) {
    data class ModelOption(val displayName: String, val providerId: String, val modelId: String)

    data class ContextUsage(val percentage: Float, val totalTokens: Int, val contextLimit: Int)

    val currentSession: Session?
        get() = sessions.find { it.id == currentSessionId }

    val currentSessionStatus: SessionStatus?
        get() = currentSessionId?.let { sessionStatuses[it] }

    val isCurrentSessionBusy: Boolean
        get() = currentSessionStatus?.isBusy == true

    val visibleAgents: List<AgentInfo>
        get() = agents.filter { it.isVisible }

    /** Curated model list (filtered like iOS), not the full API response. */
    val availableModels: List<ModelOption>
        get() = ModelPresets.list

    private val providerModelsIndex: Map<String, ProviderModel>
        get() = providers?.providers?.flatMap { provider ->
            provider.models.map { (_, model) ->
                "${provider.id}/${model.id}" to model
            }
        }?.toMap() ?: emptyMap()

    val contextUsage: ContextUsage?
        get() {
            val lastAssistant = messages.lastOrNull { it.info.isAssistant && it.info.tokens != null }
                ?: return null
            val tokens = lastAssistant.info.tokens ?: return null
            val total = tokens.total ?: return null
            val model = lastAssistant.info.resolvedModel ?: return null
            val key = "${model.providerId}/${model.modelId}"
            val limit = providerModelsIndex[key]?.limit?.context ?: return null
            if (limit <= 0) return null
            return ContextUsage(
                percentage = (total.toFloat() / limit.toFloat()).coerceIn(0f, 1f),
                totalTokens = total,
                contextLimit = limit
            )
        }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val audioRecorderManager: AudioRecorderManager
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var sseJob: Job? = null
    private var pollJob: Job? = null

    init {
        loadSettings()
    }

    private fun loadSettings() {
        repository.configure(
            baseUrl = settingsManager.serverUrl,
            username = settingsManager.username,
            password = settingsManager.password
        )
        val savedModelIndex = settingsManager.selectedModelIndex
        val clampedModelIndex = savedModelIndex.coerceIn(0, ModelPresets.list.size - 1)
        if (clampedModelIndex != savedModelIndex) {
            settingsManager.selectedModelIndex = clampedModelIndex
        }
        _state.update { it.copy(
            currentSessionId = settingsManager.currentSessionId,
            selectedModelIndex = clampedModelIndex,
            selectedAgentName = settingsManager.selectedAgentName ?: "build",
            themeMode = settingsManager.themeMode
        )}
        val savedSig = settingsManager.aiBuilderLastOKSignature
        val currentSig = aiBuilderSignature(settingsManager.aiBuilderBaseURL, settingsManager.aiBuilderToken)
        if (savedSig != null && savedSig == currentSig) {
            _state.update { it.copy(aiBuilderConnectionOK = true) }
        }
    }

    fun configureServer(url: String, username: String? = null, password: String? = null) {
        settingsManager.serverUrl = url
        settingsManager.username = username
        settingsManager.password = password
        repository.configure(url, username, password)
    }

    fun getSavedConnectionSettings(): ConnectionFormSettings = ConnectionFormSettings(
        serverUrl = settingsManager.serverUrl,
        username = settingsManager.username ?: "",
        password = settingsManager.password ?: ""
    )

    fun getAIBuilderSettings(): AIBuilderSettings = AIBuilderSettings(
        baseURL = settingsManager.aiBuilderBaseURL,
        token = settingsManager.aiBuilderToken,
        customPrompt = settingsManager.aiBuilderCustomPrompt,
        terminology = settingsManager.aiBuilderTerminology
    )

    fun saveAIBuilderSettings(settings: AIBuilderSettings) {
        settingsManager.aiBuilderBaseURL = settings.baseURL
        settingsManager.aiBuilderToken = settings.token
        settingsManager.aiBuilderCustomPrompt = settings.customPrompt
        settingsManager.aiBuilderTerminology = settings.terminology
        _state.update { it.copy(aiBuilderConnectionOK = false, aiBuilderConnectionError = null) }
        settingsManager.aiBuilderLastOKSignature = null
    }

    fun testAIBuilderConnection() {
        viewModelScope.launch {
            _state.update { it.copy(isTestingAIBuilderConnection = true, aiBuilderConnectionError = null) }
            val token = AIBuildersAudioClient.sanitizeBearerToken(settingsManager.aiBuilderToken)
            if (token.isEmpty()) {
                _state.update {
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
                    val sig = aiBuilderSignature(baseURL, token)
                    settingsManager.aiBuilderLastOKSignature = sig
                    settingsManager.aiBuilderLastOKTestedAt = System.currentTimeMillis()
                    _state.update {
                        it.copy(
                            isTestingAIBuilderConnection = false,
                            aiBuilderConnectionOK = true,
                            aiBuilderConnectionError = null
                        )
                    }
                }
                .onFailure { e ->
                    settingsManager.aiBuilderLastOKSignature = null
                    _state.update {
                        it.copy(
                            isTestingAIBuilderConnection = false,
                            aiBuilderConnectionOK = false,
                            aiBuilderConnectionError = e.message ?: "Connection failed"
                        )
                    }
                }
        }
    }

    fun toggleRecording() {
        val currentState = _state.value
        val token = AIBuildersAudioClient.sanitizeBearerToken(settingsManager.aiBuilderToken)
        Log.d(
            TAG,
            "toggleRecording clicked: recording=${currentState.isRecording}, transcribing=${currentState.isTranscribing}, aiBuilderOK=${currentState.aiBuilderConnectionOK}, tokenSet=${token.isNotEmpty()}"
        )
        if (currentState.isTranscribing) {
            Log.w(TAG, "Ignoring toggle while transcription is in progress")
            _state.update {
                it.copy(speechError = "Still transcribing previous audio, please wait.")
            }
            return
        }
        if (currentState.isRecording) {
            val file = audioRecorderManager.stop()
            _state.update { it.copy(isRecording = false, isTranscribing = true) }
            if (file == null) {
                Log.e(TAG, "Recording stop returned null file")
                _state.update { it.copy(isTranscribing = false, speechError = "Recording failed: no file") }
                return
            }
            val prefix = currentState.inputText
            viewModelScope.launch {
                try {
                    Log.d(TAG, "Converting recorded audio to PCM: ${file.absolutePath}")
                    val pcmData = audioRecorderManager.convertToPCM(file)
                    val baseURL = settingsManager.aiBuilderBaseURL.trim()
                    val prompt = settingsManager.aiBuilderCustomPrompt.trim()
                    val terms = settingsManager.aiBuilderTerminology.trim()
                    Log.d(TAG, "Submitting audio for transcription: bytes=${pcmData.size}")
                    val result = AIBuildersAudioClient.transcribe(
                        baseURL = baseURL,
                        token = token,
                        pcmAudio = pcmData,
                        language = null,
                        prompt = prompt.ifEmpty { null },
                        terms = terms.ifEmpty { null },
                        onPartialTranscript = { partial ->
                            _state.update { it.copy(inputText = mergedSpeechInput(prefix, partial)) }
                        }
                    )
                    result.onSuccess { response ->
                        val cleaned = response.text.trim()
                        Log.d(TAG, "Transcription success: chars=${cleaned.length}")
                        _state.update {
                            it.copy(
                                inputText = mergedSpeechInput(prefix, cleaned),
                                isTranscribing = false
                            )
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "Transcription failed", e)
                        _state.update {
                            it.copy(
                                inputText = prefix,
                                isTranscribing = false,
                                speechError = e.message ?: "Transcription failed"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Speech processing failed", e)
                    _state.update {
                        it.copy(
                            inputText = prefix,
                            isTranscribing = false,
                            speechError = e.message ?: "Transcription failed"
                        )
                    }
                }
            }
        } else {
            if (token.isEmpty()) {
                Log.w(TAG, "Speech start blocked: missing AI Builder token")
                _state.update {
                    it.copy(speechError = "Speech recognition requires an AI Builder token. Configure it in Settings.")
                }
                return
            }
            if (!currentState.aiBuilderConnectionOK) {
                Log.w(TAG, "Speech start blocked: AI Builder connection test has not passed")
                _state.update {
                    it.copy(speechError = "AI Builder connection test has not passed. Please test in Settings first.")
                }
                return
            }
            try {
                audioRecorderManager.start()
                Log.d(TAG, "Recording started")
                _state.update { it.copy(isRecording = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                _state.update { it.copy(speechError = "Failed to start recording: ${e.message}") }
            }
        }
    }

    fun clearSpeechError() {
        _state.update { it.copy(speechError = null) }
    }

    fun setSpeechError(message: String) {
        _state.update { it.copy(speechError = message) }
    }

    private fun aiBuilderSignature(baseURL: String, token: String): String {
        val input = "$baseURL|$token"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(isConnecting = true, error = null) }
            repository.checkHealth()
                .onSuccess { health ->
                    _state.update { it.copy(
                        isConnected = health.healthy,
                        serverVersion = health.version,
                        isConnecting = false
                    )}
                    if (health.healthy) {
                        loadInitialData()
                        startSSE()
                        startBusyPolling()
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(
                        isConnected = false,
                        isConnecting = false,
                        error = e.message
                    )}
                }
        }
    }

    private fun loadInitialData() {
        loadSessions()
        loadAgents()
        loadProviders()
    }

    fun loadSessions() {
        viewModelScope.launch {
            repository.getSessions()
                .onSuccess { sessions ->
                    _state.update { it.copy(sessions = sessions) }
                    val currentId = _state.value.currentSessionId
                    if (currentId == null && sessions.isNotEmpty()) {
                        selectSession(sessions.first().id)
                    } else if (currentId != null) {
                        loadSessionStatus()
                        loadMessages(currentId)
                    }
                }
        }
    }

    private fun loadSessionStatus() {
        viewModelScope.launch {
            repository.getSessionStatus()
                .onSuccess { statuses ->
                    _state.update { it.copy(sessionStatuses = statuses) }
                }
        }
    }

    fun selectSession(sessionId: String) {
        settingsManager.currentSessionId = sessionId
        _state.update { it.copy(
            currentSessionId = sessionId,
            messages = emptyList(),
            messageLimit = 30
        )}
        loadMessages(sessionId)
        loadSessionStatus()
    }

    fun loadMessages(sessionId: String, resetLimit: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMessages = true) }
            val limit = if (resetLimit) 30 else _state.value.messageLimit
            repository.getMessages(sessionId, limit)
                .onSuccess { messages ->
                    if (sessionId == _state.value.currentSessionId) {
                        val lastAssistant = messages.lastOrNull { it.info.isAssistant }
                        val modelIndex = lastAssistant?.info?.resolvedModel?.let { model ->
                            ModelPresets.list.indexOfFirst {
                                it.providerId == model.providerId && it.modelId == model.modelId
                            }.takeIf { it >= 0 }
                        }
                        val agentName = lastAssistant?.info?.agent
                        _state.update { it.copy(
                            messages = messages,
                            messageLimit = limit,
                            isLoadingMessages = false,
                            selectedModelIndex = modelIndex ?: it.selectedModelIndex,
                            selectedAgentName = agentName ?: it.selectedAgentName
                        )}
                    } else {
                        _state.update { it.copy(isLoadingMessages = false) }
                    }
                }
                .onFailure { e ->
                    if (sessionId == _state.value.currentSessionId) {
                        _state.update { it.copy(
                            isLoadingMessages = false,
                            error = "Failed to load messages: ${e.message}"
                        )}
                    } else {
                        _state.update { it.copy(isLoadingMessages = false) }
                    }
                }
        }
    }

    /** Load messages with delay when triggered by SSE/send (server may need time to persist). */
    private fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        viewModelScope.launch {
            delay(400)
            if (sessionId == _state.value.currentSessionId) {
                loadMessages(sessionId, resetLimit)
            }
        }
    }

    fun loadMoreMessages() {
        val sessionId = _state.value.currentSessionId ?: return
        if (_state.value.isLoadingMessages) return
        val newLimit = _state.value.messageLimit + 30
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMessages = true) }
            repository.getMessages(sessionId, newLimit)
                .onSuccess { messages ->
                    if (sessionId == _state.value.currentSessionId) {
                        _state.update { it.copy(
                            messages = messages,
                            messageLimit = newLimit,
                            isLoadingMessages = false
                        )}
                    } else {
                        _state.update { it.copy(isLoadingMessages = false) }
                    }
                }
                .onFailure {
                    if (sessionId == _state.value.currentSessionId) {
                        _state.update { it.copy(isLoadingMessages = false) }
                    }
                }
        }
    }

    private fun loadAgents() {
        viewModelScope.launch {
            repository.getAgents()
                .onSuccess { agents ->
                    _state.update { it.copy(agents = agents) }
                }
        }
    }

    private fun loadProviders() {
        viewModelScope.launch {
            repository.getProviders()
                .onSuccess { providers ->
                    _state.update { it.copy(providers = providers) }
                }
                .onFailure { e -> }
        }
    }

    fun createSession(title: String? = null) {
        viewModelScope.launch {
            repository.createSession(title)
                .onSuccess { session ->
                    _state.update { it.copy(sessions = listOf(session) + it.sessions) }
                    selectSession(session.id)
                }
        }
    }

    fun updateSessionTitle(sessionId: String, title: String) {
        viewModelScope.launch {
            repository.updateSession(sessionId, title)
                .onSuccess { updated ->
                    _state.update { it.copy(
                        sessions = it.sessions.map { s -> if (s.id == sessionId) updated else s }
                    )}
                }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
                .onSuccess {
                    val newSessions = _state.value.sessions.filter { it.id != sessionId }
                    _state.update { it.copy(sessions = newSessions) }
                    if (_state.value.currentSessionId == sessionId) {
                        val newCurrent = newSessions.firstOrNull()?.id
                        if (newCurrent != null) {
                            selectSession(newCurrent)
                        } else {
                            _state.update { it.copy(
                                currentSessionId = null,
                                messages = emptyList()
                            )}
                        }
                    }
                }
        }
    }

    fun sendMessage() {
        val sessionId = _state.value.currentSessionId ?: return
        val text = _state.value.inputText.trim()
        if (text.isEmpty()) return

        val agent = _state.value.selectedAgentName
        val selectedModel = _state.value.availableModels.getOrNull(_state.value.selectedModelIndex)
        val model = selectedModel?.let {
            Message.ModelInfo(it.providerId, it.modelId)
        } ?: _state.value.providers?.default?.let {
            Message.ModelInfo(it.providerId, it.modelId)
        }

        viewModelScope.launch {
            repository.sendMessage(sessionId, text, agent, model)
                .onSuccess {
                    _state.update { it.copy(inputText = "", error = null) }
                    loadMessagesWithRetry(sessionId)
                    launch { delay(1200); loadMessagesWithRetry(sessionId, resetLimit = false) }
                }
                .onFailure { e ->
                    _state.update { it.copy(error = e.message) }
                }
        }
    }

    fun abortSession() {
        val sessionId = _state.value.currentSessionId ?: return
        viewModelScope.launch {
            repository.abortSession(sessionId)
        }
    }

    fun setInputText(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun selectAgent(agentName: String) {
        settingsManager.selectedAgentName = agentName
        _state.update { it.copy(selectedAgentName = agentName) }
    }

    fun toggleSessionExpanded(sessionId: String) {
        _state.update { state ->
            val next = if (state.expandedSessionIds.contains(sessionId)) {
                state.expandedSessionIds - sessionId
            } else {
                state.expandedSessionIds + sessionId
            }
            state.copy(expandedSessionIds = next)
        }
    }

    fun selectModel(index: Int) {
        val clamped = index.coerceIn(0, ModelPresets.list.size - 1)
        settingsManager.selectedModelIndex = clamped
        _state.update { it.copy(selectedModelIndex = clamped) }
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsManager.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun respondPermission(sessionId: String, permissionId: String, response: PermissionResponse) {
        viewModelScope.launch {
            repository.respondPermission(sessionId, permissionId, response)
                .onSuccess {
                    _state.update { it.copy(
                        pendingPermissions = it.pendingPermissions.filter { p -> p.id != permissionId }
                    )}
                }
        }
    }

    fun loadPendingPermissions() {
        viewModelScope.launch {
            repository.getPendingPermissions()
                .onSuccess { permissions ->
                    _state.update { it.copy(pendingPermissions = permissions) }
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun showFileInFiles(path: String) {
        _state.update { it.copy(filePathToShowInFiles = path) }
    }

    fun clearFileToShow() {
        _state.update { it.copy(filePathToShowInFiles = null) }
    }

    /** Poll loadMessages every 2s when session is busy, as SSE fallback. */
    private fun startBusyPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                val sessionId = _state.value.currentSessionId ?: continue
                if (!_state.value.isCurrentSessionBusy) continue
                loadMessages(sessionId, resetLimit = false)
            }
        }
    }

    private fun startSSE() {
        sseJob?.cancel()
        sseJob = viewModelScope.launch {
            repository.connectSSE()
                .catch { e ->
                    _state.update { it.copy(error = "SSE Error: ${e.message}") }
                }
                .collect { result ->
                    result.onSuccess { event -> handleSSEEvent(event) }
                        .onFailure { e ->
                            _state.update { it.copy(error = "SSE Error: ${e.message}") }
                        }
                }
        }
    }

    private fun handleSSEEvent(event: SSEEvent) {
        when (event.payload.type) {
            "session.created" -> {
                val sessionJson = event.payload.getJsonObject("session")
                if (sessionJson != null) {
                    try {
                        val session = kotlinx.serialization.json.Json.decodeFromString<Session>(sessionJson.toString())
                        _state.update { it.copy(sessions = listOf(session) + it.sessions) }
                    } catch (e: Exception) { }
                }
            }
            "session.status" -> {
                val sessionId = event.payload.getString("sessionID") ?: return
                val statusJson = event.payload.getJsonObject("status") ?: return
                try {
                    val status = kotlinx.serialization.json.Json.decodeFromString<SessionStatus>(statusJson.toString())
                    _state.update { it.copy(
                        sessionStatuses = it.sessionStatuses + (sessionId to status)
                    )}
                    if (sessionId == _state.value.currentSessionId && !status.isBusy) {
                        _state.update { it.copy(
                            streamingPartTexts = emptyMap(),
                            streamingReasoningPart = null
                        )}
                        loadMessagesWithRetry(sessionId, resetLimit = false)
                    }
                } catch (e: Exception) { }
            }
            "message.created" -> {
                val sessionId = event.payload.getString("sessionID")
                if (sessionId != null && sessionId == _state.value.currentSessionId) {
                    loadMessagesWithRetry(sessionId)
                }
            }
            "message.part.updated" -> {
                val sessionId = event.payload.getString("sessionID")
                if (sessionId == _state.value.currentSessionId) {
                val partObj = event.payload.getJsonObject("part")
                val msgId = (partObj?.get("messageID") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val partId = (partObj?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content
                val partType = (partObj?.get("type") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "text"
                val delta = event.payload.getString("delta")
                if (msgId != null && partId != null && !delta.isNullOrBlank()) {
                    val key = "$msgId:$partId"
                    val prev = _state.value.streamingPartTexts[key] ?: ""
                    _state.update { it.copy(
                        streamingPartTexts = it.streamingPartTexts + (key to (prev + delta)),
                        streamingReasoningPart = if (partType == "reasoning") {
                            Part(id = partId, messageId = msgId, sessionId = sessionId, type = "reasoning")
                        } else it.streamingReasoningPart
                    )}
                } else {
                    _state.update { it.copy(streamingPartTexts = emptyMap(), streamingReasoningPart = null) }
                    loadMessagesWithRetry(sessionId!!, resetLimit = false)
                }
                }
            }
            "permission.asked" -> {
                loadPendingPermissions()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sseJob?.cancel()
        pollJob?.cancel()
    }

    private companion object {
        private const val TAG = "MainViewModel"
    }
}

fun mergedSpeechInput(prefix: String, transcript: String): String {
    val cleaned = transcript.trim()
    if (cleaned.isEmpty()) return prefix
    if (prefix.isEmpty()) return cleaned
    return "$prefix $cleaned"
}
