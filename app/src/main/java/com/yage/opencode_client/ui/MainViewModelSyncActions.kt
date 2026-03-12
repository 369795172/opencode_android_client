package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

internal fun handleIncomingSseEvent(
    state: MutableStateFlow<AppState>,
    event: SSEEvent,
    onRefreshMessages: (String, Boolean) -> Unit,
    onLoadPendingPermissions: () -> Unit,
    onNonFatalIssue: (String) -> Unit
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
        }
        "message.part.updated" -> {
            val deltaEvent = parseMessagePartDeltaEvent(event) ?: return
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
    }
}
