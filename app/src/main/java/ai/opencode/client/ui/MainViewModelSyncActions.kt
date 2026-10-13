package ai.opencode.client.ui

import ai.opencode.client.data.model.SSEEvent
import ai.opencode.client.data.model.TodoItem
import ai.opencode.client.data.repository.OpenCodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

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

/** Records a user-message SSE event for the round counter when the payload
 *  carries an `info` object with a user role. Dedup happens downstream
 *  (seen-ID sets), so calling on both created and updated is safe. */
private fun recordUserMessageForStats(
    state: MutableStateFlow<AppState>,
    event: SSEEvent,
    sessionId: String,
    onRecordUserMessage: (String, String) -> Unit
) {
    if (sessionId != state.value.currentSessionId) return
    val info = parseMessageInfo(event) ?: return
    if (info.role == "user") {
        onRecordUserMessage(sessionId, info.id)
    }
}

internal fun handleIncomingSseEvent(
    state: MutableStateFlow<AppState>,
    event: SSEEvent,
    onRefreshMessages: (String, Boolean) -> Unit,
    onRefreshSessions: () -> Unit,
    onLoadPendingPermissions: () -> Unit,
    onNonFatalIssue: (String) -> Unit,
    onSessionReplyComplete: (String) -> Unit = {},
    onRecordUserMessage: (String, String) -> Unit = { _, _ -> },
    onRecordToolPart: (String, String) -> Unit = { _, _ -> }
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
                if (statusEvent.sessionId == state.value.currentSessionId && !statusEvent.status.isBusy) {
                    state.update {
                        it.copy(
                            streamingPartTexts = emptyMap(),
                            streamingReasoningPart = null
                        )
                    }
                    onRefreshSessions()
                    onRefreshMessages(statusEvent.sessionId, false)
                    onSessionReplyComplete(statusEvent.sessionId)
                }
            } else {
                onNonFatalIssue("Ignoring invalid session.status payload")
            }
        }
        "message.created" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null && sessionId == state.value.currentSessionId) {
                recordUserMessageForStats(state, event, sessionId, onRecordUserMessage)
                onRefreshMessages(sessionId, true)
            }
        }
        "message.updated" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null && sessionId == state.value.currentSessionId) {
                recordUserMessageForStats(state, event, sessionId, onRecordUserMessage)
                onRefreshMessages(sessionId, false)
            }
        }
        "message.part.updated" -> {
            val deltaEvent = parseMessagePartDeltaEvent(event) ?: return
            if (deltaEvent.sessionId == state.value.currentSessionId) {
                if (deltaEvent.partType == "tool" && deltaEvent.partId != null) {
                    onRecordToolPart(deltaEvent.sessionId, deltaEvent.partId)
                }
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
        "todo.updated" -> {
            val sessionId = event.payload.getString("sessionID") ?: return
            val todosArray = event.payload.properties?.get("todos") as? kotlinx.serialization.json.JsonArray ?: return
            val todos = try {
                Json.decodeFromJsonElement<List<TodoItem>>(todosArray)
            } catch (_: Exception) {
                return
            }
            state.update { it.copy(sessionTodos = it.sessionTodos + (sessionId to todos)) }
        }
        "session.error" -> {
            val sessionId = event.payload.getString("sessionID")
            if (sessionId != null && sessionId == state.value.currentSessionId) {
                // prompt_async acknowledges with 204 before the turn runs, so a failure
                // that happens before the user message is persisted only surfaces here.
                // The server will never echo our id, so drop the pending optimistic rows
                // and hand their text back to the composer so the user can retry.
                val reason = parseSessionErrorReason(event)
                state.update { state ->
                    if (state.pendingOptimisticMessageIds.isEmpty()) {
                        state.copy(error = "Send failed: $reason")
                    } else {
                        val pendingIds = state.pendingOptimisticMessageIds
                        val recoveredText = state.messages
                            .filter { m -> m.info.id in pendingIds }
                            .mapNotNull { row -> row.parts.firstOrNull { p -> p.isText }?.text }
                            .filter { text -> text.isNotBlank() }
                            .joinToString("\n")
                        state.copy(
                            messages = state.messages.filter { m -> m.info.id !in pendingIds },
                            pendingOptimisticMessageIds = emptySet(),
                            inputText = recoveredText.ifBlank { state.inputText },
                            error = "Send failed: $reason"
                        )
                    }
                }
                onRefreshMessages(sessionId, false)
            }
        }
    }
}
