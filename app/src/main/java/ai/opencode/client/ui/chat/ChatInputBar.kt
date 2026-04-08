package ai.opencode.client.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ai.opencode.client.data.model.FileAttachment
import ai.opencode.client.data.model.PermissionRequest
import ai.opencode.client.data.model.PermissionResponse
import ai.opencode.client.ui.AsyncRequestPhase
import ai.opencode.client.ui.AsyncRequestState

@Composable
internal fun ChatInputBar(
    text: String,
    isBusy: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    isSpeechConfigured: Boolean,
    attachments: List<FileAttachment> = emptyList(),
    isLoadingFiles: Boolean = false,
    activeRequest: AsyncRequestState? = null,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    onToggleRecording: () -> Unit,
    onAttachFiles: () -> Unit = {},
    onRetryRequest: () -> Unit = {},
    onDismissRequestState: () -> Unit = {}
) {
    val density = LocalDensity.current
    var textFieldHeightPx by remember { mutableIntStateOf(0) }
    var useVerticalActions by remember { mutableStateOf(false) }
    LaunchedEffect(textFieldHeightPx) {
        useVerticalActions = with(density) {
            resolveChatActionsVerticalLayout(
                textFieldHeight = textFieldHeightPx.toDp(),
                wasVertical = useVerticalActions
            )
        }
    }

    Column {
        if (activeRequest != null) {
            RequestStateBanner(
                state = activeRequest,
                onRetry = onRetryRequest,
                onDismiss = onDismissRequestState
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().imePadding(),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = if (useVerticalActions) Alignment.Bottom else Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f).onGloballyPositioned { textFieldHeightPx = it.size.height },
                    placeholder = { Text("Type a message...") },
                    maxLines = 4,
                    enabled = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                ChatInputActions(
                    isBusy = isBusy,
                    isRecording = isRecording,
                    isTranscribing = isTranscribing,
                    isSpeechConfigured = isSpeechConfigured,
                    useVerticalActions = useVerticalActions,
                    canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !isTranscribing,
                    hasAttachments = attachments.isNotEmpty(),
                    attachmentCount = attachments.size,
                    isLoadingFiles = isLoadingFiles,
                    onAbort = onAbort,
                    onToggleRecording = onToggleRecording,
                    onSend = onSend,
                    onAttachFiles = onAttachFiles
                )
            }
        }
    }
}

@Composable
private fun RequestStateBanner(
    state: AsyncRequestState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val label = when (state.phase) {
        AsyncRequestPhase.QUEUED -> "Queued"
        AsyncRequestPhase.ACCEPTED_204 -> "Accepted (204), waiting for progress"
        AsyncRequestPhase.RUNNING -> "Running"
        AsyncRequestPhase.FIRST_ASSISTANT_SEEN -> "Assistant responded, finalizing"
        AsyncRequestPhase.RETRYING -> "Retrying request"
        AsyncRequestPhase.STALLED -> "Stalled: no progress"
        AsyncRequestPhase.FAILED -> "Failed"
        AsyncRequestPhase.COMPLETED -> "Completed"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (state.phase) {
                AsyncRequestPhase.STALLED, AsyncRequestPhase.FAILED -> MaterialTheme.colorScheme.errorContainer
                AsyncRequestPhase.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.errorMessage?.let { "$label: $it" } ?: label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall
            )
            if (state.phase == AsyncRequestPhase.STALLED) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            TextButton(onClick = onDismiss) { Text("Hide") }
        }
    }
}

@Composable
private fun ChatInputActions(
    isBusy: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    isSpeechConfigured: Boolean,
    useVerticalActions: Boolean,
    canSend: Boolean,
    hasAttachments: Boolean = false,
    attachmentCount: Int = 0,
    isLoadingFiles: Boolean = false,
    onAbort: () -> Unit,
    onToggleRecording: () -> Unit,
    onSend: () -> Unit,
    onAttachFiles: () -> Unit = {}
) {
    if (useVerticalActions) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ChatInputActionButton(
                isBusy = isBusy,
                isRecording = isRecording,
                isTranscribing = isTranscribing,
                isSpeechConfigured = isSpeechConfigured,
                canSend = canSend,
                hasAttachments = hasAttachments,
                attachmentCount = attachmentCount,
                isLoadingFiles = isLoadingFiles,
                onAbort = onAbort,
                onToggleRecording = onToggleRecording,
                onSend = onSend,
                onAttachFiles = onAttachFiles
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatInputActionButton(
                isBusy = isBusy,
                isRecording = isRecording,
                isTranscribing = isTranscribing,
                isSpeechConfigured = isSpeechConfigured,
                canSend = canSend,
                hasAttachments = hasAttachments,
                attachmentCount = attachmentCount,
                isLoadingFiles = isLoadingFiles,
                onAbort = onAbort,
                onToggleRecording = onToggleRecording,
                onSend = onSend,
                onAttachFiles = onAttachFiles
            )
        }
    }
}

@Composable
private fun ChatInputActionButton(
    isBusy: Boolean,
    isRecording: Boolean,
    isTranscribing: Boolean,
    isSpeechConfigured: Boolean,
    canSend: Boolean,
    hasAttachments: Boolean = false,
    attachmentCount: Int = 0,
    isLoadingFiles: Boolean = false,
    onAbort: () -> Unit,
    onToggleRecording: () -> Unit,
    onSend: () -> Unit,
    onAttachFiles: () -> Unit = {}
) {
    if (isBusy) {
        IconButton(onClick = onAbort, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
        }
    }
    
    IconButton(
        onClick = onAttachFiles,
        enabled = !isBusy && !isLoadingFiles && attachmentCount < FileAttachment.MAX_FILES_PER_MESSAGE
    ) {
        if (isLoadingFiles) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = "Attach file",
                tint = when {
                    hasAttachments -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
    
    IconButton(onClick = onToggleRecording, enabled = !isTranscribing) {
        if (isTranscribing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.Mic,
                contentDescription = "Speech",
                tint = when {
                    isRecording -> Color.Red
                    isSpeechConfigured -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                }
            )
        }
    }
    
    IconButton(onClick = onSend, enabled = canSend) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
    }
}

@Composable
internal fun ChatPermissionCard(
    permission: PermissionRequest,
    onRespond: (PermissionResponse) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Permission Required",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                permission.permission ?: "Unknown permission",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            permission.metadata?.filepath?.let {
                SelectionContainer {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.size(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onRespond(PermissionResponse.REJECT) }) {
                    Text("Reject")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { onRespond(PermissionResponse.ONCE) }) {
                    Text("Allow Once")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onRespond(PermissionResponse.ALWAYS) }) {
                    Text("Always Allow")
                }
            }
        }
    }
}
