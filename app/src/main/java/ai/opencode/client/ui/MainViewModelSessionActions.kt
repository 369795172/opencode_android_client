package ai.opencode.client.ui

import ai.opencode.client.data.api.PromptRequest
import ai.opencode.client.data.model.FileAttachment
import ai.opencode.client.data.model.Message
import ai.opencode.client.data.model.MessageWithParts
import ai.opencode.client.data.model.AgentInfo
import ai.opencode.client.data.model.ConfigProvider
import ai.opencode.client.data.model.ProvidersResponse
import ai.opencode.client.data.model.SessionStatus
import ai.opencode.client.data.repository.OpenCodeRepository
import ai.opencode.client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun launchLoadSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onSelectSession: (String) -> Unit,
    onLoadSessionStatus: () -> Unit,
    onLoadMessages: (String) -> Unit
) {
    scope.launch {
        val limit = MainViewModelTimings.sessionPageSize
        state.update {
            it.copy(
                loadedSessionLimit = limit,
                hasMoreSessions = true,
                isLoadingMoreSessions = false,
                isRefreshingSessions = true
            )
        }
        repository.getSessions(limit)
            .onSuccess { sessions ->
                state.update {
                    it.copy(
                        sessions = sessions,
                        hasMoreSessions = sessions.size >= limit,
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false
                    )
                }
                val currentId = state.value.currentSessionId
                val hasCurrentSession = currentId != null && sessions.any { it.id == currentId }
                when {
                    currentId == null && sessions.isNotEmpty() -> onSelectSession(sessions.first().id)
                    hasCurrentSession -> {
                        onLoadSessionStatus()
                        onLoadMessages(currentId!!)
                    }
                    sessions.isNotEmpty() -> {
                        onSelectSession(sessions.first().id)
                    }
                    else -> {
                        state.update { it.copy(currentSessionId = null, messages = emptyList()) }
                    }
                }
            }
            .onFailure { error ->
                state.update {
                    it.copy(
                        isLoadingMoreSessions = false,
                        isRefreshingSessions = false,
                        error = "Failed to load sessions: ${errorMessageOrFallback(error, "unknown error")}"
                    )
                }
            }
    }
}

internal fun launchLoadMoreSessions(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onSelectSession: (String) -> Unit
) {
    var nextLimit = 0
    var shouldLaunch = false
    state.update { current ->
        if (!current.hasMoreSessions || current.isLoadingMoreSessions) {
            current
        } else {
            nextLimit = nextSessionFetchLimit(current.loadedSessionLimit)
            shouldLaunch = true
            current.copy(isLoadingMoreSessions = true)
        }
    }
    if (!shouldLaunch) return
    scope.launch {
        repository.getSessions(nextLimit)
            .onSuccess { sessions ->
                if (state.value.loadedSessionLimit > nextLimit) {
                    state.update { it.copy(isLoadingMoreSessions = false) }
                    return@onSuccess
                }
                state.update {
                    it.copy(
                        sessions = sessions,
                        loadedSessionLimit = nextLimit,
                        hasMoreSessions = sessions.size >= nextLimit,
                        isLoadingMoreSessions = false
                    )
                }
                val currentId = state.value.currentSessionId
                val hasCurrentSession = currentId != null && sessions.any { it.id == currentId }
                when {
                    currentId == null && sessions.isNotEmpty() -> onSelectSession(sessions.first().id)
                    hasCurrentSession -> Unit
                    sessions.isNotEmpty() -> onSelectSession(sessions.first().id)
                    else -> state.update { it.copy(currentSessionId = null, messages = emptyList()) }
                }
            }
            .onFailure { error ->
                state.update {
                    it.copy(
                        isLoadingMoreSessions = false,
                        error = "Failed to load more sessions: ${errorMessageOrFallback(error, "unknown error")}"
                    )
                }
            }
    }
}

internal fun launchLoadSessionStatus(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>
) {
    scope.launch {
        repository.getSessionStatus()
            .onSuccess { statuses ->
                state.update { it.copy(sessionStatuses = statuses) }
            }
            .onFailure { error ->
                reportNonFatalIssue("MainViewModel", "Failed to load session status", error)
            }
    }
}

internal fun selectSessionState(
    state: MutableStateFlow<AppState>,
    settingsManager: SettingsManager,
    sessionId: String
) {
    val oldSessionId = state.value.currentSessionId
    val currentInputText = state.value.inputText
    if (oldSessionId != null) {
        settingsManager.setDraftText(oldSessionId, currentInputText)
    }

    settingsManager.currentSessionId = sessionId
    val restoredDraft = settingsManager.getDraftText(sessionId)
    state.update {
        it.copy(
            currentSessionId = sessionId,
            messages = emptyList(),
            messageLimit = 30,
            inputText = restoredDraft
        )
    }
}

internal fun launchLoadMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    resetLimit: Boolean = true,
    settingsManager: SettingsManager? = null
) {
    scope.launch {
        state.update { it.copy(isLoadingMessages = true) }
        val limit = if (resetLimit) 30 else state.value.messageLimit
        repository.getMessages(sessionId, limit)
            .onSuccess { messages ->
                if (sessionId == state.value.currentSessionId) {
                    val healthUpdate = extractLatestAssistantHealth(messages)
                    val mergedHealth = mergeModelHealth(state.value.modelHealth, healthUpdate)
                    val lastAssistant = messages.lastOrNull { it.info.isAssistant }
                    val inferredModelIndex = lastAssistant?.info?.resolvedModel?.let { model ->
                        state.value.availableModels.indexOfFirst {
                            it.providerId == model.providerId && it.modelId == model.modelId
                        }.takeIf { it >= 0 }
                    }
                    val inferredAgentName = lastAssistant?.info?.agent
                    val rawModelIndex = settingsManager?.getModelForSession(sessionId) ?: inferredModelIndex
                    val models = state.value.availableModels
                    val maxIdx = (models.size - 1).coerceAtLeast(0)
                    val modelIndex = rawModelIndex?.coerceIn(0, maxIdx)
                    val agentName = settingsManager?.getAgentForSession(sessionId) ?: inferredAgentName
                    val displayModels = resolveAvailableModels(ModelPresets.list, state.value.providers)
                    val remappedIndex = remapSelectedModelIndex(
                        previousList = models,
                        newList = displayModels,
                        previousIndex = modelIndex ?: state.value.selectedModelIndex
                    )
                    state.update {
                        it.copy(
                            messages = messages,
                            messageLimit = limit,
                            isLoadingMessages = false,
                            selectedModelIndex = remappedIndex.coerceIn(0, (displayModels.size - 1).coerceAtLeast(0)),
                            selectedAgentName = agentName ?: it.selectedAgentName,
                            modelHealth = mergedHealth,
                            availableModels = displayModels
                        )
                    }
                    settingsManager?.let { persistModelHealth(it, mergedHealth) }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
            .onFailure { error ->
                if (sessionId == state.value.currentSessionId) {
                    state.update {
                        it.copy(
                            isLoadingMessages = false,
                            error = "Failed to load messages: ${errorMessageOrFallback(error, "unknown error")}"
                        )
                    }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
    }
}

internal fun launchLoadMessagesWithRetry(
    scope: CoroutineScope,
    sessionId: String,
    state: MutableStateFlow<AppState>,
    resetLimit: Boolean = true,
    onLoadMessages: (String, Boolean) -> Unit
) {
    scope.launch {
        delay(MainViewModelTimings.messageRetryDelayMs)
        if (sessionId == state.value.currentSessionId) {
            onLoadMessages(sessionId, resetLimit)
        }
    }
}

internal fun launchLoadMoreMessages(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String
) {
    if (state.value.isLoadingMessages) return
    val newLimit = state.value.messageLimit + 30
    scope.launch {
        state.update { it.copy(isLoadingMessages = true) }
        repository.getMessages(sessionId, newLimit)
            .onSuccess { messages ->
                if (sessionId == state.value.currentSessionId) {
                    state.update {
                        it.copy(
                            messages = messages,
                            messageLimit = newLimit,
                            isLoadingMessages = false
                        )
                    }
                } else {
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
            .onFailure {
                if (sessionId == state.value.currentSessionId) {
                    reportNonFatalIssue("MainViewModel", "Failed to load more messages")
                    state.update { it.copy(isLoadingMessages = false) }
                }
            }
    }
}

internal fun launchLoadProviders(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    settingsManager: SettingsManager,
    onNonFatalError: (String, Throwable?) -> Unit
) {
    scope.launch {
        repository.getProviders()
            .onSuccess { providers ->
                val prev = state.value
                val newList = resolveAvailableModels(ModelPresets.list, providers)
                val newIndex = remapSelectedModelIndex(
                    prev.availableModels,
                    newList,
                    prev.selectedModelIndex
                )
                settingsManager.selectedModelIndex = newIndex
                state.update {
                    it.copy(
                        providers = providers,
                        availableModels = newList,
                        selectedModelIndex = newIndex
                    )
                }
            }
            .onFailure { error ->
                onNonFatalError("Failed to load providers", error)
            }
    }
}

internal fun launchCreateSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    title: String?,
    onSelectSession: (String) -> Unit
) {
    scope.launch {
        repository.createSession(title)
            .onSuccess { session ->
                state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to create session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchForkSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    messageId: String?,
    onSelectSession: (String) -> Unit
) {
    scope.launch {
        repository.forkSession(sessionId, messageId)
            .onSuccess { session ->
                state.update { it.copy(sessions = upsertSession(it.sessions, session)) }
                onSelectSession(session.id)
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to fork session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchUpdateSessionTitle(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    title: String
) {
    scope.launch {
        repository.updateSession(sessionId, title)
            .onSuccess { updated ->
                state.update {
                    it.copy(sessions = it.sessions.map { session -> if (session.id == sessionId) updated else session })
                }
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to update session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun launchDeleteSession(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    onSelectSession: (String) -> Unit
) {
    scope.launch {
        repository.deleteSession(sessionId)
            .onSuccess {
                val newSessions = state.value.sessions.filter { it.id != sessionId }
                state.update { it.copy(sessions = newSessions) }
                if (state.value.currentSessionId == sessionId) {
                    val newCurrent = newSessions.firstOrNull()?.id
                    if (newCurrent != null) {
                        onSelectSession(newCurrent)
                    } else {
                        state.update { it.copy(currentSessionId = null, messages = emptyList()) }
                    }
                }
            }
            .onFailure { error ->
                state.update { it.copy(error = "Failed to delete session: ${errorMessageOrFallback(error, "unknown error")}") }
            }
    }
}

internal fun buildSelectedModel(state: AppState): Message.ModelInfo? {
    val selectedModel = state.availableModels.getOrNull(state.selectedModelIndex)
    return selectedModel?.let {
        Message.ModelInfo(it.providerId, it.modelId)
    } ?: state.providers?.default?.let {
        Message.ModelInfo(it.providerId, it.modelId)
    }
}

internal fun launchSendMessage(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    sessionId: String,
    text: String,
    agent: String,
    model: Message.ModelInfo?,
    attachments: List<FileAttachment> = emptyList(),
    sessionDirectory: String? = null,
    workspaceDirectory: String = "",
    providers: ProvidersResponse? = null,
    agents: List<AgentInfo> = emptyList(),
    onRefreshMessages: (String, Boolean) -> Unit,
    onSuccess: (() -> Unit)? = null,
    onDiagnostic: (RequestDiagnosticEntry) -> Unit,
    onRequestState: (AsyncRequestState?) -> Unit,
    onError: (String) -> Unit
) {
    scope.launch {
        val parts = buildMessageParts(text, attachments)
        val enableAsyncTracking = providers?.providers?.isNotEmpty() == true || agents.isNotEmpty()
        val effectiveDirectory = sessionDirectory?.ifBlank { null } ?: workspaceDirectory.ifBlank { null }
        val request = AsyncRequestState(
            sessionId = sessionId,
            agent = agent,
            model = model,
            phase = AsyncRequestPhase.QUEUED
        )
        onRequestState(request)
        onDiagnostic(
            RequestDiagnosticEntry(
                sessionId = sessionId,
                requestId = request.requestId,
                phase = AsyncRequestPhase.QUEUED,
                agent = agent,
                providerId = model?.providerId,
                modelId = model?.modelId,
                message = "Queued prompt_async request"
            )
        )
        val preflight = runSendPreflight(
            model = model,
            agentName = agent,
            providers = providers,
            agents = agents,
            directory = effectiveDirectory
        )
        if (!preflight.ok) {
            val failure = preflight.failure!!
            onRequestState(request.copy(
                phase = AsyncRequestPhase.FAILED,
                errorCode = failure.code,
                errorMessage = failure.message
            ))
            onDiagnostic(
                RequestDiagnosticEntry(
                    sessionId = sessionId,
                    requestId = request.requestId,
                    phase = AsyncRequestPhase.FAILED,
                    agent = agent,
                    providerId = model?.providerId,
                    modelId = model?.modelId,
                    code = failure.code,
                            category = classifyFailureCategory(failure.message),
                    message = failure.message
                )
            )
            onError(failure.message)
            return@launch
        }
        val baselineMessages = repository.getMessages(sessionId).getOrDefault(emptyList())
        val baselineAssistantCount = baselineMessages.count { it.info.isAssistant }
        repository.sendMessage(sessionId, parts, agent, model, effectiveDirectory)
            .onSuccess {
                state.update {
                    it.copy(
                        inputText = "",
                        pendingAttachments = emptyList(),
                        error = null,
                        sessionStatuses = it.sessionStatuses + (sessionId to SessionStatus(type = "busy"))
                    )
                }
                onRequestState(
                    request.copy(
                        phase = AsyncRequestPhase.ACCEPTED_204,
                        lastProgressAtMs = System.currentTimeMillis()
                    )
                )
                onDiagnostic(
                    RequestDiagnosticEntry(
                        sessionId = sessionId,
                        requestId = request.requestId,
                        phase = AsyncRequestPhase.ACCEPTED_204,
                        agent = agent,
                        providerId = model?.providerId,
                        modelId = model?.modelId,
                        message = "prompt_async accepted (204)"
                    )
                )
                onSuccess?.invoke()
                onRefreshMessages(sessionId, true)
                launch {
                    delay(MainViewModelTimings.messageRefreshDelayMs)
                    onRefreshMessages(sessionId, false)
                }
                if (!enableAsyncTracking) {
                    onRequestState(null)
                } else {
                    launch {
                        trackAsyncCompletion(
                            repository = repository,
                            sessionId = sessionId,
                            request = request,
                            baselineAssistantCount = baselineAssistantCount,
                            onDiagnostic = onDiagnostic,
                            onRequestState = onRequestState,
                            onError = onError,
                            onRetry = { nextAttempt ->
                                val retryRequest = request.copy(retryCount = nextAttempt)
                                repository.sendMessage(sessionId, parts, agent, model, effectiveDirectory)
                                    .onSuccess {
                                        onDiagnostic(
                                            RequestDiagnosticEntry(
                                                sessionId = sessionId,
                                                requestId = request.requestId,
                                                phase = AsyncRequestPhase.ACCEPTED_204,
                                                agent = agent,
                                                providerId = model?.providerId,
                                                modelId = model?.modelId,
                                                message = "Retry accepted (attempt=${nextAttempt + 1})"
                                            )
                                        )
                                        trackAsyncCompletion(
                                            repository = repository,
                                            sessionId = sessionId,
                                            request = retryRequest,
                                            baselineAssistantCount = baselineAssistantCount,
                                            onDiagnostic = onDiagnostic,
                                            onRequestState = onRequestState,
                                            onError = onError,
                                            onRetry = { /* bounded retries handled by outer loop */ }
                                        )
                                    }
                                    .onFailure { retryError ->
                                        val msg = errorMessageOrFallback(retryError, "Retry failed")
                                        onRequestState(
                                            retryRequest.copy(
                                                phase = AsyncRequestPhase.FAILED,
                                                errorCode = RequestErrorCode.SESSION_STUCK,
                                                errorMessage = msg
                                            )
                                        )
                                        onDiagnostic(
                                            RequestDiagnosticEntry(
                                                sessionId = sessionId,
                                                requestId = request.requestId,
                                                phase = AsyncRequestPhase.FAILED,
                                                agent = agent,
                                                providerId = model?.providerId,
                                                modelId = model?.modelId,
                                                code = RequestErrorCode.SESSION_STUCK,
                                                category = classifyFailureCategory(msg),
                                                message = "Retry failed: $msg"
                                            )
                                        )
                                        onError(msg)
                                    }
                            }
                        )
                    }
                }
            }
            .onFailure { error ->
                val msg = errorMessageOrFallback(error, "Failed to send message")
                onRequestState(
                    request.copy(
                        phase = AsyncRequestPhase.FAILED,
                        errorCode = RequestErrorCode.SESSION_STUCK,
                        errorMessage = msg
                    )
                )
                onDiagnostic(
                    RequestDiagnosticEntry(
                        sessionId = sessionId,
                        requestId = request.requestId,
                        phase = AsyncRequestPhase.FAILED,
                        agent = agent,
                        providerId = model?.providerId,
                        modelId = model?.modelId,
                        code = RequestErrorCode.SESSION_STUCK,
                        category = classifyFailureCategory(msg),
                        message = msg
                    )
                )
                onError(msg)
            }
    }
}

private fun buildMessageParts(
    text: String,
    attachments: List<FileAttachment>
): List<PromptRequest.PartInput> {
    val parts = mutableListOf<PromptRequest.PartInput>()
    
    if (text.isNotBlank()) {
        parts.add(PromptRequest.PartInput.text(text))
    }
    
    attachments.forEach { attachment ->
        val base64 = attachment.base64Content ?: return@forEach
        parts.add(PromptRequest.PartInput.file(
            mime = attachment.mime,
            filename = attachment.filename,
            url = "data:${attachment.mime};base64,$base64"
        ))
    }
    
    return parts
}

internal fun runSendPreflight(
    model: Message.ModelInfo?,
    agentName: String,
    providers: ProvidersResponse?,
    agents: List<AgentInfo>,
    directory: String?
): SendPreflightResult {
    val providerList = providers?.providers.orEmpty()
    if (model != null && providerList.isNotEmpty()) {
        val provider = providerList.find { it.id == model.providerId }
        if (provider == null) {
            return SendPreflightResult(
                ok = false,
                failure = SendPreflightFailure(
                    code = RequestErrorCode.INVALID_MODEL,
                    message = "Provider '${model.providerId}' is not available on server."
                )
            )
        }
        val providerModel = provider.models[model.modelId]
        if (providerModel == null) {
            return SendPreflightResult(
                ok = false,
                failure = SendPreflightFailure(
                    code = RequestErrorCode.INVALID_MODEL,
                    message = "Model '${model.providerId}/${model.modelId}' is not available."
                )
            )
        }
        if (!isProviderModelSelectable(providerModel)) {
            return SendPreflightResult(
                ok = false,
                failure = SendPreflightFailure(
                    code = RequestErrorCode.INVALID_MODEL,
                    message = "Model '${model.providerId}/${model.modelId}' is not active."
                )
            )
        }
    }
    if (agents.isNotEmpty()) {
        val selectedAgent = agents.find { it.name == agentName }
            ?: return SendPreflightResult(
                ok = false,
                failure = SendPreflightFailure(
                    code = RequestErrorCode.INVALID_AGENT,
                    message = "Agent '$agentName' does not exist on server."
                )
            )
        if (selectedAgent.requiresDirectory() && directory.isNullOrBlank()) {
            return SendPreflightResult(
                ok = false,
                failure = SendPreflightFailure(
                    code = RequestErrorCode.MISSING_DIRECTORY,
                    message = "Agent '$agentName' requires a workspace directory."
                )
            )
        }
    }
    return SendPreflightResult(ok = true)
}

private fun mergeModelHealth(
    current: Map<String, ModelHealth>,
    update: ModelHealthUpdate?
): Map<String, ModelHealth> {
    if (update == null) return current
    return current + (update.key to update.health)
}

private data class ModelHealthUpdate(
    val key: String,
    val health: ModelHealth
)

private fun extractLatestAssistantHealth(messages: List<MessageWithParts>): ModelHealthUpdate? {
    val assistant = messages.lastOrNull { it.info.isAssistant } ?: return null
    val model = assistant.info.resolvedModel ?: return null
    val key = "${model.providerId}/${model.modelId}"
    val error = assistant.info.error?.message?.toString()
    val text = assistant.parts.firstOrNull { it.isText }?.text?.trim().orEmpty()
    val health = when {
        !error.isNullOrBlank() -> ModelHealth(
            healthy = false,
            updatedAtMs = System.currentTimeMillis(),
            reason = error
        )
        text.isNotBlank() -> ModelHealth(
            healthy = true,
            updatedAtMs = System.currentTimeMillis(),
            reason = null
        )
        else -> return null
    }
    return ModelHealthUpdate(key = key, health = health)
}

private suspend fun trackAsyncCompletion(
    repository: OpenCodeRepository,
    sessionId: String,
    request: AsyncRequestState,
    baselineAssistantCount: Int,
    onDiagnostic: (RequestDiagnosticEntry) -> Unit,
    onRequestState: (AsyncRequestState?) -> Unit,
    onError: (String) -> Unit,
    onRetry: suspend (Int) -> Unit
) {
    var current = request.copy(phase = AsyncRequestPhase.RUNNING, lastProgressAtMs = System.currentTimeMillis())
    onRequestState(current)
    onDiagnostic(
        RequestDiagnosticEntry(
            sessionId = sessionId,
            requestId = request.requestId,
            phase = AsyncRequestPhase.RUNNING,
            agent = request.agent,
            providerId = request.model?.providerId,
            modelId = request.model?.modelId,
            message = "Waiting for assistant progress"
        )
    )
    val startedAt = System.currentTimeMillis()
    while (System.currentTimeMillis() - startedAt < MainViewModelTimings.requestMaxTrackMs) {
        delay(MainViewModelTimings.requestTrackPollMs)
        val messages = repository.getMessages(sessionId).getOrDefault(emptyList())
        val assistantCount = messages.count { it.info.isAssistant }
        val latestAssistant = messages.lastOrNull { it.info.isAssistant }
        val assistantError = latestAssistant?.info?.error?.message
        if (!assistantError.isNullOrBlank()) {
            onRequestState(
                current.copy(
                    phase = AsyncRequestPhase.FAILED,
                    errorCode = RequestErrorCode.SESSION_STUCK,
                    errorMessage = assistantError
                )
            )
            onDiagnostic(
                RequestDiagnosticEntry(
                    sessionId = sessionId,
                    requestId = request.requestId,
                    phase = AsyncRequestPhase.FAILED,
                    agent = request.agent,
                    providerId = request.model?.providerId,
                    modelId = request.model?.modelId,
                    code = RequestErrorCode.SESSION_STUCK,
                    category = classifyFailureCategory(assistantError),
                    message = assistantError
                )
            )
            onError(assistantError)
            return
        }
        val hasAssistantProgress = assistantCount > baselineAssistantCount
        if (hasAssistantProgress && current.phase != AsyncRequestPhase.FIRST_ASSISTANT_SEEN) {
            current = current.copy(
                phase = AsyncRequestPhase.FIRST_ASSISTANT_SEEN,
                lastProgressAtMs = System.currentTimeMillis()
            )
            onRequestState(current)
            onDiagnostic(
                RequestDiagnosticEntry(
                    sessionId = sessionId,
                    requestId = request.requestId,
                    phase = AsyncRequestPhase.FIRST_ASSISTANT_SEEN,
                    agent = request.agent,
                    providerId = request.model?.providerId,
                    modelId = request.model?.modelId,
                    message = "Assistant response detected"
                )
            )
        }
        val statuses = repository.getSessionStatus().getOrDefault(emptyMap())
        val status = statuses[sessionId]
        val idle = status?.isIdle == true
        if (hasAssistantProgress && idle) {
            onRequestState(current.copy(phase = AsyncRequestPhase.COMPLETED))
            onDiagnostic(
                RequestDiagnosticEntry(
                    sessionId = sessionId,
                    requestId = request.requestId,
                    phase = AsyncRequestPhase.COMPLETED,
                    agent = request.agent,
                    providerId = request.model?.providerId,
                    modelId = request.model?.modelId,
                    message = "Request completed"
                )
            )
            return
        }
    }
    val stalledMsg = "Request accepted but no assistant progress within timeout window."
    if (request.retryCount < request.maxRetries) {
        val next = request.retryCount + 1
        onRequestState(current.copy(phase = AsyncRequestPhase.RETRYING, retryCount = next))
        onDiagnostic(
            RequestDiagnosticEntry(
                sessionId = sessionId,
                requestId = request.requestId,
                phase = AsyncRequestPhase.RETRYING,
                agent = request.agent,
                providerId = request.model?.providerId,
                modelId = request.model?.modelId,
                message = "Retrying stalled request (attempt=${next + 1})"
            )
        )
        onRetry(next)
        return
    }
    onRequestState(
        current.copy(
            phase = AsyncRequestPhase.STALLED,
            errorCode = RequestErrorCode.ASYNC_ACCEPTED_NO_PROGRESS,
            errorMessage = stalledMsg
        )
    )
    onDiagnostic(
        RequestDiagnosticEntry(
            sessionId = sessionId,
            requestId = request.requestId,
            phase = AsyncRequestPhase.STALLED,
            agent = request.agent,
            providerId = request.model?.providerId,
            modelId = request.model?.modelId,
            code = RequestErrorCode.ASYNC_ACCEPTED_NO_PROGRESS,
            category = RequestFailureCategory.TIMEOUT,
            message = stalledMsg
        )
    )
    onError(stalledMsg)
}

internal fun AgentInfo.requiresDirectory(): Boolean {
    if (native == true) return false
    return mode == "subagent"
}
