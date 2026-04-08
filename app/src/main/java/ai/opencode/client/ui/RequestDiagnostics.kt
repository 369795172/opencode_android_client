package ai.opencode.client.ui

import ai.opencode.client.data.model.Message
import java.util.UUID

enum class RequestErrorCode {
    INVALID_MODEL,
    INVALID_AGENT,
    MISSING_DIRECTORY,
    ASYNC_ACCEPTED_NO_PROGRESS,
    SESSION_STUCK
}

enum class AsyncRequestPhase {
    QUEUED,
    ACCEPTED_204,
    RUNNING,
    FIRST_ASSISTANT_SEEN,
    STALLED,
    FAILED,
    COMPLETED,
    RETRYING
}

data class AsyncRequestState(
    val requestId: String = "req_${UUID.randomUUID()}",
    val sessionId: String,
    val agent: String,
    val model: Message.ModelInfo?,
    val phase: AsyncRequestPhase = AsyncRequestPhase.QUEUED,
    val startedAtMs: Long = System.currentTimeMillis(),
    val lastProgressAtMs: Long = startedAtMs,
    val retryCount: Int = 0,
    val maxRetries: Int = 1,
    val errorCode: RequestErrorCode? = null,
    val errorMessage: String? = null
)

data class RequestDiagnosticEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val sessionId: String,
    val requestId: String?,
    val phase: AsyncRequestPhase,
    val agent: String,
    val providerId: String?,
    val modelId: String?,
    val code: RequestErrorCode? = null,
    val message: String
)

internal data class SendPreflightFailure(
    val code: RequestErrorCode,
    val message: String
)

internal data class SendPreflightResult(
    val ok: Boolean,
    val failure: SendPreflightFailure? = null
)

internal fun appendDiagnostic(
    current: List<RequestDiagnosticEntry>,
    entry: RequestDiagnosticEntry,
    limit: Int = 120
): List<RequestDiagnosticEntry> {
    val next = current + entry
    return if (next.size <= limit) next else next.takeLast(limit)
}

internal fun toDiagnosticsReport(entries: List<RequestDiagnosticEntry>): String {
    if (entries.isEmpty()) {
        return "No diagnostics captured yet."
    }
    val header = "OpenCode Android Diagnostics\nentries=${entries.size}\n"
    val body = entries.joinToString(separator = "\n") { e ->
        val model = listOfNotNull(e.providerId, e.modelId).joinToString("/")
        val code = e.code?.name ?: "-"
        val requestId = e.requestId ?: "-"
        buildString {
            append("ts=${e.timestampMs} ")
            append("session=${e.sessionId} ")
            append("request=${requestId} ")
            append("phase=${e.phase.name} ")
            append("agent=${e.agent} ")
            append("model=${if (model.isBlank()) "-" else model} ")
            append("code=$code ")
            append("msg=${e.message}")
        }
    }
    return "$header\n$body"
}
