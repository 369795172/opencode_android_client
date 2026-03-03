package com.yage.opencode_client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yage.opencode_client.data.model.*
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionFormSettings(
    val serverUrl: String,
    val username: String,
    val password: String
)

data class AppState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val sessions: List<Session> = emptyList(),
    val currentSessionId: String? = null,
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val messages: List<MessageWithParts> = emptyList(),
    val messageLimit: Int = 6,
    val isLoadingMessages: Boolean = false,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgentName: String = "build",
    val selectedModelIndex: Int = 0,
    val providers: ProvidersResponse? = null,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val inputText: String = "",
    val error: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
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

    val availableModels: List<ModelOption>
        get() = providers?.providers?.flatMap { provider ->
            provider.models.map { (_, model) ->
                ModelOption(
                    displayName = model.name ?: model.id,
                    providerId = provider.id,
                    modelId = model.id
                )
            }
        } ?: emptyList()

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
    private val settingsManager: SettingsManager
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var sseJob: Job? = null

    init {
        loadSettings()
    }

    private fun loadSettings() {
        repository.configure(
            baseUrl = settingsManager.serverUrl,
            username = settingsManager.username,
            password = settingsManager.password
        )
        _state.update { it.copy(
            currentSessionId = settingsManager.currentSessionId,
            selectedModelIndex = settingsManager.selectedModelIndex,
            selectedAgentName = settingsManager.selectedAgentName ?: "build",
            themeMode = settingsManager.themeMode
        )}
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
        _state.update { it.copy(currentSessionId = sessionId) }
        loadMessages(sessionId)
        loadSessionStatus()
    }

    fun loadMessages(sessionId: String, resetLimit: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMessages = true) }
            val limit = if (resetLimit) 6 else _state.value.messageLimit
            repository.getMessages(sessionId, limit)
                .onSuccess { messages ->
                    _state.update { it.copy(
                        messages = messages,
                        messageLimit = limit,
                        isLoadingMessages = false
                    )}
                }
                .onFailure { e ->
                    _state.update { it.copy(
                        isLoadingMessages = false,
                        error = "Failed to load messages: ${e.message}"
                    )}
                }
        }
    }

    fun loadMoreMessages() {
        val sessionId = _state.value.currentSessionId ?: return
        val newLimit = _state.value.messageLimit + 6
        viewModelScope.launch {
            repository.getMessages(sessionId, newLimit)
                .onSuccess { messages ->
                    _state.update { it.copy(
                        messages = messages,
                        messageLimit = newLimit
                    )}
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
                    _state.update { it.copy(inputText = "") }
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

    fun selectModel(index: Int) {
        settingsManager.selectedModelIndex = index
        _state.update { it.copy(selectedModelIndex = index) }
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
                } catch (e: Exception) { }
            }
            "message.created" -> {
                val sessionId = event.payload.getString("sessionID")
                if (sessionId == _state.value.currentSessionId) {
                    loadMessages(sessionId!!)
                }
            }
            "message.part.updated" -> {
                val sessionId = event.payload.getString("sessionID")
                if (sessionId == _state.value.currentSessionId) {
                    loadMessages(sessionId!!, resetLimit = false)
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
    }
}
