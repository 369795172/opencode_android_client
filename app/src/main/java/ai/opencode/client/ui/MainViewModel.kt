package ai.opencode.client.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.opencode.client.data.model.*
import ai.opencode.client.data.repository.OpenCodeRepository
import ai.opencode.client.util.FileEncoder
import ai.opencode.client.util.SettingsManager
import ai.opencode.client.util.ThemeMode
import com.yage.voiceflowkit.VoiceFlowClient
import com.yage.voiceflowkit.VoiceFlowConfig
import com.yage.voiceflowkit.VoiceFlowMicrophone
import com.yage.voiceflowkit.VoiceFlowPreservedAudio
import com.yage.voiceflowkit.VoiceFlowSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

data class ConnectionFormSettings(
    val serverUrl: String,
    val workspaceDirectory: String,
    val username: String,
    val password: String
)

data class AIBuilderSettings(
    val baseURL: String,
    val token: String,
    val customPrompt: String,
    val terminology: String
)

data class ModelHealth(
    val healthy: Boolean,
    val updatedAtMs: Long,
    val reason: String? = null
)

data class AppState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val sessions: List<Session> = emptyList(),
    val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
    val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
    val expandedSessionIds: Set<String> = emptySet(),
    val currentSessionId: String? = null,
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val messages: List<MessageWithParts> = emptyList(),
    val messageLimit: Int = 30,
    val isLoadingMessages: Boolean = false,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgentName: String = "build",
    val selectedModelIndex: Int = 0,
    val availableModels: List<ModelOption> = ModelPresets.list,
    val modelHealth: Map<String, ModelHealth> = emptyMap(),
    val providers: ProvidersResponse? = null,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    val inputText: String = "",
    val error: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val filePathToShowInFiles: String? = null,
    val filePreviewOriginRoute: String? = null,
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val toolPartLastUpdated: Map<String, Long> = emptyMap(),
    val stalledToolPartKeys: Set<String> = emptySet(),
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val hasPreservedSpeechAudio: Boolean = false,
    val isRetryingSpeech: Boolean = false,
    val speechAudioLevel: Float = 0f,
    val speechError: String? = null,
    val aiBuilderConnectionOK: Boolean = false,
    val aiBuilderConnectionError: String? = null,
    val isTestingAIBuilderConnection: Boolean = false,
    val pendingAttachments: List<FileAttachment> = emptyList(),
    val isLoadingAttachments: Boolean = false,
    val activeRequest: AsyncRequestState? = null,
    val diagnostics: List<RequestDiagnosticEntry> = emptyList(),
    val sessionTodos: Map<String, List<TodoItem>> = emptyMap()
) {
    data class ModelOption(
        val displayName: String,
        val providerId: String,
        val modelId: String,
        val modelIdPrefix: String? = null
    ) {
        val shortName: String
            get() = when {
                displayName == "DeepSeek V4 Flash" -> "DS-Flash"
                displayName == "DeepSeek V4 Pro" -> "DS-Pro"
                "Haiku" in displayName -> "Haiku"
                "DeepSeek" in displayName -> "DeepSeek"
                "Gemini" in displayName -> "Gemini"
                "GPT" in displayName -> "GPT"
                "Grok" in displayName -> "Grok"
                else -> displayName.split(" ").firstOrNull() ?: displayName
            }
    }

    data class ContextUsage(
        val percentage: Float,
        val totalTokens: Int,
        val contextLimit: Int,
        val providerId: String? = null,
        val modelId: String? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val reasoningTokens: Int? = null,
        val cachedReadTokens: Int? = null,
        val cachedWriteTokens: Int? = null,
        val cost: Double? = null
    )

    data class ConnectionState(
        val isConnected: Boolean = false,
        val isConnecting: Boolean = false,
        val serverVersion: String? = null
    )

    data class SessionState(
        val sessions: List<Session> = emptyList(),
        val currentSessionId: String? = null,
        val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
        val expandedSessionIds: Set<String> = emptySet(),
        val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
        val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
        val messageLimit: Int = 30,
        val pendingPermissions: List<PermissionRequest> = emptyList(),
        val pendingQuestions: List<QuestionRequest> = emptyList()
    ) {
        val currentSession: Session?
            get() = sessions.find { it.id == currentSessionId }

        val currentSessionStatus: SessionStatus?
            get() = currentSessionId?.let { sessionStatuses[it] }

        val isCurrentSessionBusy: Boolean
            get() = currentSessionStatus?.let { it.isBusy || it.isRetry } == true

        val canLoadMoreSessions: Boolean
            get() = hasMoreSessions && !isLoadingMoreSessions
    }

    data class ChatState(
        val messages: List<MessageWithParts> = emptyList(),
        val streamingPartTexts: Map<String, String> = emptyMap(),
        val streamingReasoningPart: Part? = null,
        val isLoadingMessages: Boolean = false,
        val inputText: String = ""
    )

    data class SpeechState(
        val isRecording: Boolean = false,
        val isTranscribing: Boolean = false,
        val speechError: String? = null,
        val isTestingAIBuilderConnection: Boolean = false,
        val aiBuilderConnectionOK: Boolean = false,
        val aiBuilderConnectionError: String? = null
    )

    data class FileUiState(
        val filePathToShowInFiles: String? = null,
        val filePreviewOriginRoute: String? = null
    )

    data class SettingsState(
        val error: String? = null,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val selectedModelIndex: Int = 2,
        val selectedAgentName: String = "build",
        val availableModels: List<ModelOption> = ModelPresets.list,
        val contextUsage: ContextUsage? = null,
        val agents: List<AgentInfo> = emptyList(),
        val providers: ProvidersResponse? = null,
        val isRecording: Boolean = false
    )

    val connectionState: ConnectionState
        get() = ConnectionState(
            isConnected = isConnected,
            isConnecting = isConnecting,
            serverVersion = serverVersion
        )

    val sessionState: SessionState
        get() = SessionState(
            sessions = sessions,
            currentSessionId = currentSessionId,
            sessionStatuses = sessionStatuses,
            expandedSessionIds = expandedSessionIds,
            loadedSessionLimit = loadedSessionLimit,
            hasMoreSessions = hasMoreSessions,
            isLoadingMoreSessions = isLoadingMoreSessions,
            isRefreshingSessions = isRefreshingSessions,
            messageLimit = messageLimit,
            pendingPermissions = pendingPermissions,
            pendingQuestions = pendingQuestions
        )

    val chatState: ChatState
        get() = ChatState(
            messages = messages,
            streamingPartTexts = streamingPartTexts,
            streamingReasoningPart = streamingReasoningPart,
            isLoadingMessages = isLoadingMessages,
            inputText = inputText
        )

    val speechState: SpeechState
        get() = SpeechState(
            isRecording = isRecording,
            isTranscribing = isTranscribing,
            speechError = speechError,
            isTestingAIBuilderConnection = isTestingAIBuilderConnection,
            aiBuilderConnectionOK = aiBuilderConnectionOK,
            aiBuilderConnectionError = aiBuilderConnectionError
        )

    val fileUiState: FileUiState
        get() = FileUiState(
            filePathToShowInFiles = filePathToShowInFiles,
            filePreviewOriginRoute = filePreviewOriginRoute
        )

    val settingsState: SettingsState
        get() = SettingsState(
            error = error,
            themeMode = themeMode,
            selectedModelIndex = selectedModelIndex,
            selectedAgentName = selectedAgentName,
            availableModels = availableModels,
            contextUsage = contextUsage,
            agents = agents,
            providers = providers,
            isRecording = isRecording
        )

    val currentSession: Session?
        get() = sessions.find { it.id == currentSessionId }

    val currentSessionStatus: SessionStatus?
        get() = currentSessionId?.let { sessionStatuses[it] }

    val isCurrentSessionBusy: Boolean
        get() = currentSessionStatus?.let { it.isBusy || it.isRetry } == true

    val canLoadMoreSessions: Boolean
        get() = hasMoreSessions && !isLoadingMoreSessions

    val visibleAgents: List<AgentInfo>
        get() = agents.filter { it.isVisible }

    private val providerModelsIndex: Map<String, ProviderModel>
        get() = providers?.providers?.flatMap { provider ->
            provider.models.flatMap { (modelKey, model) ->
                listOfNotNull(
                    "${provider.id}/$modelKey" to model,
                    model.id.takeIf { it.isNotEmpty() }?.let { "${provider.id}/$it" to model },
                    model.resolvedProviderId?.let { resolvedProvider ->
                        model.id.takeIf { it.isNotEmpty() }?.let { modelId -> "$resolvedProvider/$modelId" to model }
                    }
                )
            }
        }?.toMap() ?: emptyMap()

    val contextUsage: ContextUsage?
        get() {
            val lastAssistant = messages.lastOrNull { it.info.isAssistant && tokenTotal(it.info.tokens) != null }
                ?: return null
            val tokens = lastAssistant.info.tokens ?: return null
            val total = tokenTotal(tokens) ?: return null
            val model = lastAssistant.info.resolvedModel ?: return null
            val key = "${model.providerId}/${model.modelId}"
            val index = providerModelsIndex
            val providerModel = index[key] ?: index.entries
                .filter { it.key.substringAfter('/') == model.modelId }
                .takeIf { it.size == 1 }
                ?.first()
                ?.value
            val limit = providerModel?.limit?.context ?: return null
            if (limit <= 0) return null
            return ContextUsage(
                percentage = (total.toFloat() / limit.toFloat()).coerceIn(0f, 1f),
                totalTokens = total,
                contextLimit = limit,
                providerId = model.providerId,
                modelId = model.modelId,
                inputTokens = tokens.input,
                outputTokens = tokens.output,
                reasoningTokens = tokens.reasoning,
                cachedReadTokens = tokens.cache?.read,
                cachedWriteTokens = tokens.cache?.write,
                cost = lastAssistant.info.cost
            )
        }

    private fun tokenTotal(tokens: Message.TokenInfo?): Int? {
        if (tokens == null) return null
        tokens.total?.takeIf { it > 0 }?.let { return it }
        return listOfNotNull(
            tokens.input,
            tokens.output,
            tokens.reasoning,
            tokens.cache?.read,
            tokens.cache?.write
        ).sum().takeIf { it > 0 }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    internal val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val voiceFlowClient: VoiceFlowClient,
    private val microphone: VoiceFlowMicrophone
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var sseJob: Job? = null
    private var pollJob: Job? = null
    private var toolPartStallJob: Job? = null
    private var speechHeartbeatJob: Job? = null
    private var speechAudioLevelJob: Job? = null
    private var speechSession: VoiceFlowSession? = null
    private var speechExistingInput: String = ""
    private var preservedSpeechAudio: VoiceFlowPreservedAudio? = null
    private var preservedSpeechExistingInput: String = ""
    private var lastHealthCheckTime = 0L

    init {
        loadSettings()
    }

    private fun loadSettings() {
        applySavedSettings(repository, settingsManager, _state)
    }

    fun configureServer(
        url: String,
        workspaceDirectory: String = "",
        username: String? = null,
        password: String? = null
    ) {
        settingsManager.serverUrl = url
        settingsManager.workspaceDirectory = workspaceDirectory
        settingsManager.username = username
        settingsManager.password = password
        repository.configure(
            url,
            username,
            password,
            workspaceDirectory.ifBlank { null }
        )
    }

    fun getSavedConnectionSettings(): ConnectionFormSettings = ConnectionFormSettings(
        serverUrl = settingsManager.serverUrl,
        workspaceDirectory = settingsManager.workspaceDirectory,
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
        launchAIBuilderConnectionTest(viewModelScope, settingsManager, voiceFlowClient, _state)
    }

    fun toggleRecording() {
        val currentState = _state.value
        val speechConfig = currentSpeechInputConfig(settingsManager)
        Log.d(
            TAG,
            "toggleRecording clicked: recording=${currentState.isRecording}, transcribing=${currentState.isTranscribing}, aiBuilderOK=${currentState.aiBuilderConnectionOK}, tokenSet=${speechConfig.token.isNotEmpty()}"
        )
        if (currentState.isTranscribing) {
            Log.w(TAG, "Ignoring toggle while transcription is in progress")
            _state.update {
                it.copy(speechError = "Still transcribing previous audio, please wait.")
            }
            return
        }
        if (currentState.isRecording) {
            val session = speechSession
            viewModelScope.launch { microphone.stop() }
            stopSpeechAudioLevelConsumer()
            speechHeartbeatJob?.cancel()
            speechHeartbeatJob = null
            _state.update { it.copy(isRecording = false, isTranscribing = true) }
            if (session == null) {
                Log.e(TAG, "Realtime speech session is missing on stop")
                _state.update { it.copy(isTranscribing = false, speechError = "Recording failed: realtime session missing") }
                return
            }
            launchRealtimeSpeechStop(
                scope = viewModelScope,
                state = _state,
                session = session,
                existingInput = speechExistingInput,
                tag = TAG,
                shouldApply = { speechSession === session },
                terminateSession = ::terminateSpeechSession,
            ) {
                speechSession = null
            }
        } else {
            if (speechConfig.token.isEmpty()) {
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
            speechExistingInput = currentState.inputText
            viewModelScope.launch {
                try {
                    voiceFlowClient.updateConfig(
                        VoiceFlowConfig(
                            endpoint = speechConfig.baseURL.ifEmpty { VoiceFlowConfig.DEFAULT_ENDPOINT },
                            tokenProvider = { speechConfig.token },
                            prompt = speechConfig.prompt.ifEmpty { null },
                            terms = speechConfig.terms,
                        )
                    )
                    clearPreservedSpeechAudio()
                    val session = voiceFlowClient.startSession()
                    speechSession = session
                    startSpeechAudioLevelConsumer()
                    microphone.start { chunk ->
                        viewModelScope.launch { session.sendAudioChunk(chunk) }
                    }
                    speechHeartbeatJob?.cancel()
                    speechHeartbeatJob = viewModelScope.launch {
                        while (true) {
                            delay(SPEECH_HEARTBEAT_INTERVAL_SECONDS * 1000L)
                            session.ping()
                        }
                    }
                    Log.d(TAG, "Realtime recording started")
                    _state.update { it.copy(isRecording = true) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start recording", e)
                    runCatching { microphone.stop() }
                    stopSpeechAudioLevelConsumer()
                    speechSession?.let { session ->
                        runCatching { terminateSpeechSession(session) }
                    }
                    speechSession = null
                    speechHeartbeatJob?.cancel()
                    speechHeartbeatJob = null
                    _state.update {
                        it.copy(
                            isRecording = false,
                            speechError = "Failed to start recording: ${errorMessageOrFallback(e, "unknown error")}"
                        )
                    }
                }
            }
        }
    }

    private suspend fun terminateSpeechSession(session: VoiceFlowSession) {
        try {
            session.abortPreservingAudio()?.let { voiceFlowClient.discardPreservedAudio(it) }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to terminate speech session", error)
        }
    }

    fun stopSpeechForBackground() {
        val session = speechSession
        speechHeartbeatJob?.cancel()
        speechHeartbeatJob = null
        stopSpeechAudioLevelConsumer()
        speechSession = null
        _state.update { it.copy(isRecording = false, isTranscribing = false, speechAudioLevel = 0f) }
        viewModelScope.launch {
            runCatching { microphone.stop() }
            if (session != null) {
                terminateSpeechSession(session)
            }
        }
    }

    fun clearSpeechError() {
        _state.update { it.copy(speechError = null) }
    }

    fun abortSpeechRecognition() {
        val session = speechSession ?: return
        val prefix = speechExistingInput
        speechHeartbeatJob?.cancel()
        speechHeartbeatJob = null
        stopSpeechAudioLevelConsumer()
        speechSession = null
        _state.update { it.copy(isRecording = false, isTranscribing = false, speechAudioLevel = 0f) }
        viewModelScope.launch {
            runCatching { microphone.stop() }
            try {
                val preserved = session.abortPreservingAudio()
                clearPreservedSpeechAudio()
                if (preserved != null) {
                    preservedSpeechAudio = preserved
                    preservedSpeechExistingInput = prefix
                    _state.update { it.copy(hasPreservedSpeechAudio = true) }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Failed to abort speech recognition", error)
                _state.update { it.copy(speechError = errorMessageOrFallback(error, "Failed to abort speech recognition")) }
            }
        }
    }

    fun retryPreservedSpeechAudio() {
        val preserved = preservedSpeechAudio ?: return
        val prefix = preservedSpeechExistingInput
        _state.update { it.copy(isRetryingSpeech = true) }
        viewModelScope.launch {
            try {
                val result = voiceFlowClient.transcribe(preserved) { partial ->
                    _state.update { it.copy(inputText = mergedSpeechInput(prefix, partial)) }
                }
                _state.update {
                    it.copy(
                        inputText = mergedSpeechInput(prefix, result.text),
                        isRetryingSpeech = false,
                    )
                }
                clearPreservedSpeechAudio()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to retry preserved speech audio", error)
                _state.update {
                    it.copy(
                        isRetryingSpeech = false,
                        speechError = errorMessageOrFallback(error, "Transcription failed"),
                    )
                }
            }
        }
    }

    private fun clearPreservedSpeechAudio() {
        preservedSpeechAudio?.let { voiceFlowClient.discardPreservedAudio(it) }
        preservedSpeechAudio = null
        preservedSpeechExistingInput = ""
        _state.update { it.copy(hasPreservedSpeechAudio = false) }
    }

    fun discardPreservedSpeechAudio() {
        clearPreservedSpeechAudio()
    }

    private fun startSpeechAudioLevelConsumer() {
        speechAudioLevelJob?.cancel()
        _state.update { it.copy(speechAudioLevel = 0f) }
        speechAudioLevelJob = viewModelScope.launch {
            microphone.audioLevel.collect { level ->
                _state.update { it.copy(speechAudioLevel = level.coerceIn(0f, 1f)) }
            }
        }
    }

    private fun stopSpeechAudioLevelConsumer() {
        speechAudioLevelJob?.cancel()
        speechAudioLevelJob = null
        _state.update { it.copy(speechAudioLevel = 0f) }
    }

    fun setSpeechError(message: String) {
        _state.update { it.copy(speechError = message) }
    }

    fun testConnection() {
        val now = System.currentTimeMillis()
        if (now - lastHealthCheckTime < 30_000) return
        lastHealthCheckTime = now
        launchConnectionTest(viewModelScope, repository, _state) {
            loadInitialData()
            startSSE()
            startBusyPolling()
        }
    }

    private fun loadInitialData() {
        loadSessions()
        loadAgents()
        loadProviders()
        loadPendingQuestions()
    }

    fun loadSessions() {
        launchLoadSessions(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            onSelectSession = ::selectSession,
            onLoadSessionStatus = ::loadSessionStatus,
            onLoadMessages = { sessionId -> loadMessages(sessionId) }
        )
    }

    fun loadMoreSessions() {
        launchLoadMoreSessions(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            onSelectSession = ::selectSession
        )
    }

    private fun loadSessionStatus() {
        launchLoadSessionStatus(viewModelScope, repository, _state)
    }

    fun selectSession(sessionId: String) {
        selectSessionState(_state, settingsManager, sessionId)
        loadMessages(sessionId)
        loadSessionStatus()
    }

    fun loadMessages(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessages(viewModelScope, repository, _state, sessionId, resetLimit, settingsManager)
    }

    /** Load messages with delay when triggered by SSE/send (server may need time to persist). */
    private fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(viewModelScope, sessionId, _state, resetLimit, ::loadMessages)
    }

    fun loadMoreMessages() {
        val sessionId = _state.value.currentSessionId ?: return
        launchLoadMoreMessages(viewModelScope, repository, _state, sessionId)
    }

    private fun loadAgents() {
        viewModelScope.launch {
            repository.getAgents()
                .onSuccess { agents ->
                    _state.update { it.copy(agents = agents) }
                }
                .onFailure { error ->
                    reportNonFatalIssue(TAG, "Failed to load agents", error)
                }
        }
    }

    private fun loadProviders() {
        launchLoadProviders(viewModelScope, repository, _state, settingsManager) { message, error ->
            reportNonFatalIssue(TAG, message, error)
        }
    }

    fun createSession(title: String? = null) {
        launchCreateSession(viewModelScope, repository, _state, title, ::selectSession)
    }

    fun forkSession(sessionId: String, messageId: String?) {
        launchForkSession(viewModelScope, repository, _state, sessionId, messageId, ::selectSession)
    }

    fun updateSessionTitle(sessionId: String, title: String) {
        launchUpdateSessionTitle(viewModelScope, repository, _state, sessionId, title)
    }

    fun archiveSession(sessionId: String) {
        launchSetSessionArchived(viewModelScope, repository, _state, sessionId, archived = true)
    }

    fun restoreSession(sessionId: String) {
        launchSetSessionArchived(viewModelScope, repository, _state, sessionId, archived = false)
    }

    fun deleteSession(sessionId: String) {
        launchDeleteSession(viewModelScope, repository, _state, sessionId, ::selectSession)
    }

    fun sendMessage() {
        val snapshot = _state.value
        val sessionId = snapshot.currentSessionId ?: return
        val text = snapshot.inputText.trim()
        val attachments = snapshot.pendingAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        val agent = snapshot.selectedAgentName
        val model = buildSelectedModel(snapshot)
        val sessionDirectory = snapshot.currentSession?.directory
        val workspaceDirectory = settingsManager.workspaceDirectory
        val currentSession = snapshot.currentSession

        fun dispatchSend() {
            launchSendMessage(
                scope = viewModelScope,
                repository = repository,
                state = _state,
                sessionId = sessionId,
                text = text,
                agent = agent,
                model = model,
                attachments = attachments,
                sessionDirectory = sessionDirectory,
                workspaceDirectory = workspaceDirectory,
                providers = snapshot.providers,
                agents = snapshot.agents,
                onRefreshMessages = ::loadMessagesWithRetry,
                onRefreshSessions = ::loadSessions,
                onSuccess = {
                    settingsManager.setDraftText(sessionId, "")
                    clearAttachments()
                },
                onDiagnostic = { entry ->
                    _state.update { s ->
                        s.copy(diagnostics = appendDiagnostic(s.diagnostics, entry))
                    }
                },
                onRequestState = { request ->
                    _state.update { it.copy(activeRequest = request) }
                },
                onError = { message ->
                    setError(message)
                }
            )
        }

        if (currentSession?.isArchived == true) {
            viewModelScope.launch {
                repository.updateSessionArchived(sessionId, -1L)
                    .onSuccess { updated ->
                        _state.update { state ->
                            state.copy(sessions = state.sessions.map { session -> if (session.id == sessionId) updated else session })
                        }
                        dispatchSend()
                    }
                    .onFailure { error ->
                        _state.update { it.copy(error = "Failed to restore session: ${errorMessageOrFallback(error, "unknown error")}") }
                    }
            }
            return
        }

        dispatchSend()
    }

    fun retryStalledRequest() {
        val req = _state.value.activeRequest ?: return
        if (req.phase != AsyncRequestPhase.STALLED) return
        val snapshot = _state.value
        val sessionId = snapshot.currentSessionId ?: return
        if (req.sessionId != sessionId) return
        val lastUserMessage = snapshot.messages
            .lastOrNull { it.info.isUser }
        val retryText = lastUserMessage
            ?.parts
            ?.firstOrNull { it.isText }
            ?.text
            ?.trim()
            .orEmpty()
        val hasFilePart = lastUserMessage
            ?.parts
            ?.any { it.type == "file" }
            ?: false
        if (retryText.isEmpty() && !hasFilePart) {
            setError("No previous user message to retry. Please resend manually.")
            return
        }
        val effectiveRetryText = if (retryText.isNotEmpty()) retryText else "Please continue."

        abortSession()
        launchSendMessage(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            sessionId = sessionId,
            text = effectiveRetryText,
            agent = req.agent,
            model = req.model,
            attachments = emptyList(),
            sessionDirectory = snapshot.currentSession?.directory,
            workspaceDirectory = settingsManager.workspaceDirectory,
            providers = snapshot.providers,
            agents = snapshot.agents,
            onRefreshMessages = ::loadMessagesWithRetry,
            onRefreshSessions = ::loadSessions,
            onDiagnostic = { entry ->
                _state.update { s ->
                    s.copy(diagnostics = appendDiagnostic(s.diagnostics, entry))
                }
            },
            onRequestState = { request ->
                _state.update { it.copy(activeRequest = request) }
            },
            onError = { message ->
                setError(message)
            }
        )
    }

    fun clearActiveRequest() {
        _state.update { it.copy(activeRequest = null) }
    }

    fun exportDiagnosticsReport(): String = toDiagnosticsReport(_state.value.diagnostics)

    fun abortSession() {
        val sessionId = _state.value.currentSessionId ?: return
        viewModelScope.launch {
            repository.abortSession(sessionId)
                .onFailure { error ->
                    _state.update { it.copy(error = errorMessageOrFallback(error, "Failed to abort session")) }
                }
        }
    }

    fun setInputText(text: String) {
        _state.update { it.copy(inputText = text) }
        _state.value.currentSessionId?.let { settingsManager.setDraftText(it, text) }
    }

    fun addAttachment(attachment: FileAttachment) {
        if (!attachment.isTextFile) {
            _state.update { it.copy(error = "Unsupported file type: ${attachment.filename}") }
            return
        }
        val current = _state.value.pendingAttachments
        if (current.size >= FileAttachment.MAX_FILES_PER_MESSAGE) {
            _state.update { it.copy(error = "Maximum ${FileAttachment.MAX_FILES_PER_MESSAGE} attachments allowed") }
            return
        }
        if (attachment.sizeBytes > FileAttachment.MAX_FILE_SIZE) {
            _state.update { it.copy(error = "File too large (max ${FileAttachment.MAX_FILE_SIZE / 1_000_000}MB)") }
            return
        }
        _state.update { 
            it.copy(
                pendingAttachments = current + attachment,
                error = null
            ) 
        }
    }

    fun removeAttachment(attachment: FileAttachment) {
        _state.update { 
            it.copy(pendingAttachments = _state.value.pendingAttachments - attachment) 
        }
    }

    fun clearAttachments() {
        _state.update { it.copy(pendingAttachments = emptyList()) }
    }

    fun loadFiles(uris: List<Uri>, context: Context) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingAttachments = true, error = null) }
            try {
                for (uri in uris) {
                    if (_state.value.pendingAttachments.size >= FileAttachment.MAX_FILES_PER_MESSAGE) {
                        _state.update {
                            it.copy(error = "Maximum ${FileAttachment.MAX_FILES_PER_MESSAGE} attachments allowed")
                        }
                        break
                    }
                    FileEncoder.loadAttachment(context, uri)
                        .onSuccess { attachment -> addAttachment(attachment) }
                        .onFailure { error ->
                            _state.update {
                                it.copy(error = error.message ?: "Failed to read file")
                            }
                        }
                }
            } finally {
                _state.update { it.copy(isLoadingAttachments = false) }
            }
        }
    }

    fun selectAgent(agentName: String) {
        settingsManager.selectedAgentName = agentName
        _state.update { it.copy(selectedAgentName = agentName) }
        _state.value.currentSessionId?.let { settingsManager.setAgentForSession(it, agentName) }
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
        val models = _state.value.availableModels
        val maxIdx = (models.size - 1).coerceAtLeast(0)
        val clamped = index.coerceIn(0, maxIdx)
        settingsManager.selectedModelIndex = clamped
        _state.update { it.copy(selectedModelIndex = clamped) }
        _state.value.currentSessionId?.let { settingsManager.setModelForSession(it, clamped) }
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
                .onFailure { error ->
                    _state.update { it.copy(error = errorMessageOrFallback(error, "Failed to respond to permission")) }
                }
        }
    }

    fun loadPendingPermissions() {
        viewModelScope.launch {
            repository.getPendingPermissions()
                .onSuccess { permissions ->
                    _state.update { it.copy(pendingPermissions = permissions) }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to load permissions: ${error.message}")
                }
        }
    }

    fun loadPendingQuestions() {
        viewModelScope.launch {
            repository.getPendingQuestions()
                .onSuccess { questions ->
                    _state.update { it.copy(pendingQuestions = questions) }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to load questions: ${error.message}")
                }
        }
    }

    fun replyQuestion(requestId: String, answers: List<List<String>>, onError: () -> Unit = {}) {
        viewModelScope.launch {
            repository.replyQuestion(requestId, answers)
                .onSuccess {
                    _state.update { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to reply question: ${error.message}")
                    onError()
                }
        }
    }

    fun rejectQuestion(requestId: String) {
        viewModelScope.launch {
            repository.rejectQuestion(requestId)
                .onSuccess {
                    _state.update { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to reject question: ${error.message}")
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun setError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun showFileInFiles(path: String, originRoute: String? = null) {
        _state.update { it.copy(filePathToShowInFiles = path, filePreviewOriginRoute = originRoute) }
    }

    fun clearFileToShow() {
        _state.update { it.copy(filePathToShowInFiles = null, filePreviewOriginRoute = null) }
    }

    /** Poll loadMessages every 2s when session is busy, as SSE fallback. */
    private fun startBusyPolling() {
        pollJob?.cancel()
        pollJob = launchBusyPolling(viewModelScope, _state, ::loadMessages)
    }

    private fun startSSE() {
        sseJob?.cancel()
        toolPartStallJob?.cancel()
        sseJob = launchSseCollection(viewModelScope, repository, _state, ::handleSSEEvent)
        toolPartStallJob = launchToolPartStallMonitor(viewModelScope, _state)
    }

    private fun handleSSEEvent(event: SSEEvent) {
        handleIncomingSseEvent(
            state = _state,
            event = event,
            onRefreshMessages = ::loadMessagesWithRetry,
            onRefreshSessions = ::loadSessions,
            onLoadPendingPermissions = ::loadPendingPermissions,
            onNonFatalIssue = { message -> reportNonFatalIssue(TAG, message) }
        )
    }

    override fun onCleared() {
        sseJob?.cancel()
        pollJob?.cancel()
        toolPartStallJob?.cancel()
        speechHeartbeatJob?.cancel()
        microphone.discard()
        runBlocking { speechSession?.let { terminateSpeechSession(it) } }
        speechSession = null
        super.onCleared()
    }

    private companion object {
        private const val TAG = "MainViewModel"

        /** Mirrors VoiceFlowKit's internal heartbeat cadence (12s ping). */
        private const val SPEECH_HEARTBEAT_INTERVAL_SECONDS = 12L
    }
}
