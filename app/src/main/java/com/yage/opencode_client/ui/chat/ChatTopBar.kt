package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.data.model.AgentInfo
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.session.SessionList

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    sessions: List<Session>,
    currentSessionId: String?,
    expandedSessionIds: Set<String> = emptySet(),
    agents: List<AgentInfo>,
    selectedAgent: String,
    availableModels: List<AppState.ModelOption>,
    selectedModelIndex: Int,
    contextUsage: AppState.ContextUsage?,
    onSelectSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onToggleSessionExpanded: (String) -> Unit = {},
    onSelectAgent: (String) -> Unit,
    onSelectModel: (Int) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    showSettingsButton: Boolean = true,
    showNewSessionInTopBar: Boolean = true,
    showSessionListInTopBar: Boolean = true
) {
    var showSessionSheet by remember { mutableStateOf(false) }
    var showAgentMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            val currentSession = sessions.find { it.id == currentSessionId }
            Text(
                text = currentSession?.title ?: currentSession?.directory?.split("/")?.lastOrNull() ?: "OpenCode",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            contextUsage?.let { usage ->
                ContextUsageRing(usage = usage)
                Spacer(modifier = Modifier.width(4.dp))
            }

            Box {
                IconButton(onClick = { showModelMenu = true }) {
                    Icon(Icons.Default.Tune, contentDescription = "Switch LLM model")
                }
                DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                    if (availableModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No models", color = MaterialTheme.colorScheme.outline) },
                            onClick = { }
                        )
                    }
                    availableModels.forEachIndexed { index, model ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    model.displayName,
                                    color = if (index == selectedModelIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                onSelectModel(index)
                                showModelMenu = false
                            }
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { showAgentMenu = true }) {
                    Icon(Icons.Default.SmartToy, contentDescription = "Agent")
                }
                DropdownMenu(expanded = showAgentMenu, onDismissRequest = { showAgentMenu = false }) {
                    if (agents.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No agents", color = MaterialTheme.colorScheme.outline) },
                            onClick = { }
                        )
                    }
                    agents.forEach { agent ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    agent.name,
                                    color = if (agent.name == selectedAgent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                onSelectAgent(agent.name)
                                showAgentMenu = false
                            }
                        )
                    }
                }
            }

            if (showSessionListInTopBar) {
                IconButton(onClick = { showSessionSheet = true }) {
                    Icon(Icons.Default.List, contentDescription = "Sessions")
                }
            }
            if (showSessionSheet) {
                ModalBottomSheet(onDismissRequest = { showSessionSheet = false }) {
                    Box(modifier = Modifier.fillMaxWidth().height(ChatUiTuning.sessionSheetHeight)) {
                        SessionList(
                            sessions = sessions,
                            currentSessionId = currentSessionId,
                            expandedSessionIds = expandedSessionIds,
                            onSelectSession = {
                                onSelectSession(it)
                                showSessionSheet = false
                            },
                            onCreateSession = {
                                onCreateSession()
                                showSessionSheet = false
                            },
                            onDeleteSession = {
                                onDeleteSession(it)
                                showSessionSheet = false
                            },
                            onToggleSessionExpanded = onToggleSessionExpanded,
                            onOpenSettings = null
                        )
                    }
                }
            }
            if (showSettingsButton) {
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }
    )
}

@Composable
internal fun ContextUsageRing(usage: AppState.ContextUsage) {
    val ringColor = when {
        usage.percentage >= 0.9f -> MaterialTheme.colorScheme.error
        usage.percentage >= 0.7f -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.primary
    }

    Box(modifier = Modifier.size(ChatUiTuning.contextRingOuterSize), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(ChatUiTuning.contextRingInnerSize),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            strokeWidth = 3.dp
        )
        CircularProgressIndicator(
            progress = { usage.percentage },
            modifier = Modifier.size(ChatUiTuning.contextRingInnerSize),
            color = ringColor,
            strokeWidth = 3.dp
        )
    }
}

@Composable
internal fun ChatEmptyState(
    isConnected: Boolean,
    onConnect: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Chat,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                if (isConnected) "Select or create a session" else "Connect to server",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (!isConnected) {
                Button(onClick = onConnect, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text("Connect")
                }
            }
        }
    }
}
