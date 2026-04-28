package ai.opencode.client.ui

import ai.opencode.client.data.model.SSEEvent
import ai.opencode.client.data.repository.OpenCodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TOOL_PART_STALL_CHECK_INTERVAL_MS = 30_000L
private const val TOOL_PART_STALL_TIMEOUT_MS = 120_000L

internal fun launchBusyPolling(
    scope: CoroutineScope,
    state: MutableStateFlow<AppState>,
    onLoadMessages: (String, Boolean) -> Unit
): Job {
    return scope.launch {
        while (true) {
            delay(MainViewModelTimings.busyPollingIntervalMs)
            val sessionId = state.value.currentSessionId ?: continue
            if (state.value.isLoadingMessages) continue
            if (!state.value.isCurrentSessionBusy) continue
            onLoadMessages(sessionId, false)
        }
    }
}

internal fun launchSseCollection(
    scope: CoroutineScope,
    repository: OpenCodeRepository,
    state: MutableStateFlow<AppState>,
    onEvent: (SSEEvent) -> Unit
): Job {
    return scope.launch {
        repository.connectSSE()
            .catch { error ->
                state.update { it.copy(error = "SSE Error: ${error.message}") }
            }
            .collect { result ->
                result.onSuccess { event -> onEvent(event) }
                    .onFailure { error ->
                        state.update { it.copy(error = "SSE Error: ${error.message}") }
                    }
            }
    }
}

internal fun launchToolPartStallMonitor(
    scope: CoroutineScope,
    state: MutableStateFlow<AppState>
): Job {
    return scope.launch {
        while (true) {
            delay(TOOL_PART_STALL_CHECK_INTERVAL_MS)
            val now = System.currentTimeMillis()
            state.update { current ->
                val runningKeys = current.messages.asSequence()
                    .flatMap { msg ->
                        msg.parts.asSequence()
                            .filter { it.isTool && it.stateDisplay == "running" }
                            .map { part -> "${msg.info.id}:${part.id}" }
                    }
                    .toSet()

                if (runningKeys.isEmpty()) {
                    current.copy(toolPartLastUpdated = emptyMap(), stalledToolPartKeys = emptySet())
                } else {
                    val nextLastUpdated = current.toolPartLastUpdated
                        .filterKeys { it in runningKeys }
                        .toMutableMap()
                    runningKeys.forEach { key ->
                        if (nextLastUpdated[key] == null) nextLastUpdated[key] = now
                    }
                    val stalled = runningKeys.filterTo(mutableSetOf()) { key ->
                        now - (nextLastUpdated[key] ?: now) >= TOOL_PART_STALL_TIMEOUT_MS
                    }
                    current.copy(
                        toolPartLastUpdated = nextLastUpdated,
                        stalledToolPartKeys = stalled
                    )
                }
            }
        }
    }
}

internal fun handleIncomingSseEvent(
    state: MutableStateFlow<AppState>,
    event: SSEEvent,
    onRefreshMessages: (String, Boolean) -> Unit,
    onLoadPendingPermissions: () -> Unit,
    onNonFatalIssue: (String) -> Unit,
    onDiagnostic: (RequestDiagnosticEntry) -> Unit
) {
    when (event.payload.type) {
        "session.created" -> {
            val created = parseSessionCreatedEvent(event)
            if (created != null) {
                state.update { it.copy(sessions = upsertSession(it.sessions, created.session)) }
            } else {
                onNonFatalIssue("Ignoring invalid session.created payload")
            }
        }
        "session.updated" -> {
            val updated = parseSessionUpdatedEvent(event)
            if (updated != null) {
                state.update { it.copy(sessions = upsertSession(it.sessions, updated)) }
            } else {
                onNonFatalIssue("Ignoring invalid session.updated payload")
            }
        }
        "session.status" -> {
            val statusEvent = parseSessionStatusEvent(event)
            if (statusEvent != null) {
                state.update {
                    it.copy(
                        sessionStatuses = it.sessionStatuses + (statusEvent.sessionId to statusEvent.status)
                    )
                }
                if (statusEvent.status.isRetry && !statusEvent.status.message.isNullOrBlank()) {
                    val retryMsg = "Retry #${statusEvent.status.attempt ?: "?"}: ${statusEvent.status.message}"
                    state.update { it.copy(error = retryMsg) }
                }
                val active = state.value.activeRequest
                if (active != null && active.sessionId == statusEvent.sessionId && statusEvent.status.isIdle) {
                    val completed = active.copy(phase = AsyncRequestPhase.COMPLETED, lastProgressAtMs = System.currentTimeMillis())
                    state.update { it.copy(activeRequest = completed) }
                    onDiagnostic(
                        RequestDiagnosticEntry(
                            sessionId = statusEvent.sessionId,
                            requestId = active.requestId,
                            phase = AsyncRequestPhase.COMPLETED,
                            agent = active.agent,
                            providerId = active.model?.providerId,
                            modelId = active.model?.modelId,
                            message = "Session reported idle after request"
                        )
                    )
                }
                if (statusEvent.sessionId == state.value.currentSessionId && !statusEvent.status.isBusy) {
                    state.update {
                        it.copy(
                            streamingPartTexts = emptyMap(),
                            streamingReasoningPart = null
                        )
                    }
                    onRefreshMessages(statusEvent.sessionId, false)
                }
            } else {
                onNonFatalIssue("Ignoring invalid session.status payload")
            }
        }
        "message.created" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null && sessionId == state.value.currentSessionId) {
                onRefreshMessages(sessionId, true)
            }
            if (sessionId != null) {
                val active = state.value.activeRequest
                if (active != null && active.sessionId == sessionId) {
                    val progressed = active.copy(
                        phase = AsyncRequestPhase.FIRST_ASSISTANT_SEEN,
                        lastProgressAtMs = System.currentTimeMillis()
                    )
                    state.update { it.copy(activeRequest = progressed) }
                    onDiagnostic(
                        RequestDiagnosticEntry(
                            sessionId = sessionId,
                            requestId = active.requestId,
                            phase = AsyncRequestPhase.FIRST_ASSISTANT_SEEN,
                            agent = active.agent,
                            providerId = active.model?.providerId,
                            modelId = active.model?.modelId,
                            message = "message.created observed via SSE"
                        )
                    )
                }
            }
        }
        "message.part.updated" -> {
            val deltaEvent = parseMessagePartDeltaEvent(event) ?: return
            if (
                deltaEvent.partType == "tool" &&
                deltaEvent.messageId != null &&
                deltaEvent.partId != null
            ) {
                val key = "${deltaEvent.messageId}:${deltaEvent.partId}"
                val now = System.currentTimeMillis()
                state.update {
                    it.copy(
                        toolPartLastUpdated = it.toolPartLastUpdated + (key to now),
                        stalledToolPartKeys = it.stalledToolPartKeys - key
                    )
                }
            }
            if (deltaEvent.sessionId == state.value.currentSessionId) {
                if (
                    deltaEvent.messageId != null &&
                    deltaEvent.partId != null &&
                    !deltaEvent.delta.isNullOrBlank()
                ) {
                    val key = "${deltaEvent.messageId}:${deltaEvent.partId}"
                    val previousValue = state.value.streamingPartTexts[key] ?: ""
                    state.update {
                        it.copy(
                            streamingPartTexts = it.streamingPartTexts + (key to (previousValue + deltaEvent.delta)),
                            streamingReasoningPart = reasoningPartOrNull(
                                partType = deltaEvent.partType,
                                partId = deltaEvent.partId,
                                messageId = deltaEvent.messageId,
                                sessionId = deltaEvent.sessionId
                            ) ?: it.streamingReasoningPart
                        )
                    }
                } else {
                    state.update {
                        it.copy(streamingPartTexts = emptyMap(), streamingReasoningPart = null)
                    }
                    onRefreshMessages(deltaEvent.sessionId, false)
                }
            }
        }
        "permission.asked" -> {
            onLoadPendingPermissions()
        }
        "question.asked" -> {
            val question = parseQuestionAskedEvent(event)
            if (question != null) {
                state.update { currentState ->
                    val existing = currentState.pendingQuestions.any { it.id == question.id }
                    if (!existing) {
                        currentState.copy(pendingQuestions = currentState.pendingQuestions + question)
                    } else {
                        currentState
                    }
                }
            } else {
                onNonFatalIssue("Ignoring invalid question.asked payload")
            }
        }
        "question.replied", "question.rejected" -> {
            val requestId = event.payload.getString("requestID") 
                ?: event.payload.getString("id")
            if (requestId != null) {
                state.update { currentState ->
                    currentState.copy(
                        pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId }
                    )
                }
            }
        }
    }
}
