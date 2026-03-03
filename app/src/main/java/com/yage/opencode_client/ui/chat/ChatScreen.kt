package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mikepenz.markdown.m3.Markdown
import com.yage.opencode_client.data.model.*
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.theme.UserMessageBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateToFiles: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            sessions = state.sessions,
            currentSessionId = state.currentSessionId,
            isBusy = state.isCurrentSessionBusy,
            agents = state.visibleAgents,
            selectedAgent = state.selectedAgentName,
            availableModels = state.availableModels,
            selectedModelIndex = state.selectedModelIndex,
            contextUsage = state.contextUsage,
            onSelectSession = { viewModel.selectSession(it) },
            onCreateSession = { viewModel.createSession() },
            onAbort = { viewModel.abortSession() },
            onSelectAgent = { viewModel.selectAgent(it) },
            onSelectModel = { viewModel.selectModel(it) }
        )

        Box(modifier = Modifier.weight(1f)) {
            if (state.currentSessionId == null) {
                EmptyState(
                    isConnected = state.isConnected,
                    onConnect = { viewModel.testConnection() }
                )
            } else {
                MessageList(
                    messages = state.messages,
                    isLoading = state.isLoadingMessages,
                    onLoadMore = { viewModel.loadMoreMessages() },
                    onFileClick = onNavigateToFiles
                )
            }

            state.error?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }

        if (state.currentSessionId != null) {
            InputBar(
                text = state.inputText,
                isBusy = state.isCurrentSessionBusy,
                onTextChange = { viewModel.setInputText(it) },
                onSend = { viewModel.sendMessage() }
            )
        }

        state.pendingPermissions.firstOrNull()?.let { permission ->
            PermissionCard(
                permission = permission,
                onRespond = { response ->
                    viewModel.respondPermission(permission.sessionId, permission.id, response)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    sessions: List<Session>,
    currentSessionId: String?,
    isBusy: Boolean,
    agents: List<AgentInfo>,
    selectedAgent: String,
    availableModels: List<AppState.ModelOption>,
    selectedModelIndex: Int,
    contextUsage: AppState.ContextUsage?,
    onSelectSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onAbort: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onSelectModel: (Int) -> Unit
) {
    var showSessionMenu by remember { mutableStateOf(false) }
    var showAgentMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            val currentSession = sessions.find { it.id == currentSessionId }
            Text(currentSession?.title ?: currentSession?.directory?.split("/")?.lastOrNull() ?: "OpenCode")
        },
        actions = {
            contextUsage?.let { usage ->
                ContextUsageRing(usage = usage)
                Spacer(modifier = Modifier.width(4.dp))
            }

            IconButton(onClick = { showModelMenu = true }) {
                Icon(Icons.Default.Tune, contentDescription = "Model")
            }
            DropdownMenu(
                expanded = showModelMenu,
                onDismissRequest = { showModelMenu = false }
            ) {
                availableModels.forEachIndexed { index, model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                model.displayName,
                                color = if (index == selectedModelIndex)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            onSelectModel(index)
                            showModelMenu = false
                        }
                    )
                }
            }

            IconButton(onClick = { showAgentMenu = true }) {
                Icon(Icons.Default.SmartToy, contentDescription = "Agent")
            }
            DropdownMenu(
                expanded = showAgentMenu,
                onDismissRequest = { showAgentMenu = false }
            ) {
                agents.forEach { agent ->
                    DropdownMenuItem(
                        text = { Text(agent.shortName) },
                        onClick = {
                            onSelectAgent(agent.name)
                            showAgentMenu = false
                        }
                    )
                }
            }

            if (isBusy) {
                IconButton(onClick = onAbort) {
                    Icon(Icons.Default.Stop, contentDescription = "Abort")
                }
            }

            IconButton(onClick = { showSessionMenu = true }) {
                Icon(Icons.Default.List, contentDescription = "Sessions")
            }
            DropdownMenu(
                expanded = showSessionMenu,
                onDismissRequest = { showSessionMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("New Session") },
                    onClick = {
                        onCreateSession()
                        showSessionMenu = false
                    }
                )
                HorizontalDivider()
                sessions.forEach { session ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                session.title ?: session.directory.split("/").lastOrNull() ?: session.id,
                                color = if (session.id == currentSessionId)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            onSelectSession(session.id)
                            showSessionMenu = false
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun ContextUsageRing(usage: AppState.ContextUsage) {
    val ringColor = when {
        usage.percentage >= 0.9f -> MaterialTheme.colorScheme.error
        usage.percentage >= 0.7f -> Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = Modifier.size(28.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(22.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            strokeWidth = 3.dp
        )
        CircularProgressIndicator(
            progress = { usage.percentage },
            modifier = Modifier.size(22.dp),
            color = ringColor,
            strokeWidth = 3.dp
        )
    }
}

@Composable
private fun EmptyState(
    isConnected: Boolean,
    onConnect: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
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
                Button(onClick = onConnect) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<MessageWithParts>,
    isLoading: Boolean,
    onLoadMore: () -> Unit,
    onFileClick: (String) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(messages, key = { it.info.id }) { message ->
            MessageRow(
                message = message,
                onFileClick = onFileClick
            )
        }
        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: MessageWithParts,
    onFileClick: (String) -> Unit
) {
    val isUser = message.info.isUser

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        message.parts.forEach { part ->
            PartView(
                part = part,
                isUser = isUser,
                onFileClick = onFileClick
            )
        }
    }
}

@Composable
private fun PartView(
    part: Part,
    isUser: Boolean,
    onFileClick: (String) -> Unit
) {
    when {
        part.isText -> {
            TextPart(
                text = part.text ?: "",
                isUser = isUser
            )
        }
        part.isReasoning -> {
            ReasoningCard(
                text = part.text ?: "",
                title = part.toolReason
            )
        }
        part.isTool -> {
            ToolCard(
                toolName = part.tool ?: "",
                status = part.stateDisplay,
                reason = part.toolReason,
                input = part.toolInputSummary,
                output = part.toolOutput,
                todos = part.toolTodos,
                filePaths = part.filePathsForNavigation,
                onFileClick = onFileClick
            )
        }
        part.isPatch -> {
            PatchCard(
                filePaths = part.filePathsForNavigation,
                onFileClick = onFileClick
            )
        }
    }
}

@Composable
private fun TextPart(
    text: String,
    isUser: Boolean
) {
    val modifier = Modifier
        .fillMaxWidth()
        .then(
            if (isUser) Modifier.background(
                UserMessageBackground,
                RoundedCornerShape(8.dp)
            ) else Modifier
        )
        .padding(12.dp)

    if (isUser) {
        Text(
            text = text,
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium
        )
    } else {
        Markdown(
            content = text,
            modifier = modifier
        )
    }
}

@Composable
private fun ReasoningCard(
    text: String,
    title: String?
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title ?: "Thinking",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (expanded && text.isNotBlank()) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ToolCard(
    toolName: String,
    status: String?,
    reason: String?,
    input: String?,
    output: String?,
    todos: List<TodoItem>,
    filePaths: List<String>,
    onFileClick: (String) -> Unit
) {
    val isRunning = status == "running"
    var expanded by remember { mutableStateOf(isRunning) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    reason ?: toolName,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))

                input?.let {
                    Text(
                        "Input:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                if (todos.isNotEmpty()) {
                    Text(
                        "Tasks:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    todos.forEach { todo ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Icon(
                                if (todo.isCompleted) Icons.Default.CheckCircle else Icons.Default.Circle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (todo.isCompleted)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                todo.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (todo.isCompleted)
                                    MaterialTheme.colorScheme.outline
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                filePaths.forEach { path ->
                    TextButton(
                        onClick = { onFileClick(path) },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchCard(
    filePaths: List<String>,
    onFileClick: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Patch", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.height(8.dp))
            filePaths.forEach { path ->
                TextButton(
                    onClick = { onFileClick(path) },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    permission: PermissionRequest,
    onRespond: (PermissionResponse) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Permission Required",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                permission.permission ?: "Unknown permission",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            permission.metadata?.filepath?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
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

@Composable
private fun InputBar(
    text: String,
    isBusy: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                maxLines = 4,
                enabled = !isBusy
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = text.isNotBlank() && !isBusy
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send"
                )
            }
        }
    }
}
