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
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.m3.Markdown
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yage.opencode_client.data.model.*
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.MainViewModel
import androidx.compose.foundation.isSystemInDarkTheme
import com.yage.opencode_client.ui.theme.markdownTypographyCompact
import com.yage.opencode_client.ui.theme.ToolWritePatchBackgroundDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onNavigateToFiles: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    showSettingsButton: Boolean = true,
    showNewSessionInTopBar: Boolean = true,
    showSessionListInTopBar: Boolean = true
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
            onSelectModel = { viewModel.selectModel(it) },
            onNavigateToSettings = onNavigateToSettings,
            showSettingsButton = showSettingsButton,
            showNewSessionInTopBar = showNewSessionInTopBar,
            showSessionListInTopBar = showSessionListInTopBar
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
                    streamingPartTexts = state.streamingPartTexts,
                    streamingReasoningPart = state.streamingReasoningPart,
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
    onSelectModel: (Int) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    showSettingsButton: Boolean = true,
    showNewSessionInTopBar: Boolean = true,
    showSessionListInTopBar: Boolean = true
) {
    var showSessionMenu by remember { mutableStateOf(false) }
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
                DropdownMenu(
                    expanded = showModelMenu,
                    onDismissRequest = { showModelMenu = false }
                ) {
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
            }

            Box {
                IconButton(onClick = { showAgentMenu = true }) {
                    Icon(Icons.Default.SmartToy, contentDescription = "Agent")
                }
                DropdownMenu(
                    expanded = showAgentMenu,
                    onDismissRequest = { showAgentMenu = false }
                ) {
                    if (agents.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No agents", color = MaterialTheme.colorScheme.outline) },
                            onClick = { }
                        )
                    }
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
            }

            if (isBusy) {
                IconButton(onClick = onAbort) {
                    Icon(Icons.Default.Stop, contentDescription = "Abort")
                }
            }

            if (showSessionListInTopBar) {
            Box {
                IconButton(onClick = { showSessionMenu = true }) {
                    Icon(Icons.Default.List, contentDescription = "Sessions")
                }
                DropdownMenu(
                    expanded = showSessionMenu,
                    onDismissRequest = { showSessionMenu = false }
                ) {
                if (showNewSessionInTopBar) {
                    DropdownMenuItem(
                        text = { Text("New Session") },
                        onClick = {
                            onCreateSession()
                            showSessionMenu = false
                        }
                    )
                    HorizontalDivider()
                }
                sessions.forEach { session ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                session.displayName,
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
                Button(
                    onClick = onConnect,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<MessageWithParts>,
    streamingPartTexts: Map<String, String>,
    streamingReasoningPart: Part?,
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
        if (streamingReasoningPart != null) {
            val streamingKey = "${streamingReasoningPart.messageId}:${streamingReasoningPart.id}"
            val streamingText = streamingPartTexts[streamingKey] ?: ""
            item(key = "streaming-reasoning") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    ReasoningCard(
                        text = streamingText,
                        title = streamingReasoningPart.toolReason,
                        isStreaming = true
                    )
                }
            }
        }
        items(messages.reversed(), key = { it.info.id }) { message ->
            MessageRow(
                message = message,
                streamingPartTexts = streamingPartTexts,
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
        if (!isLoading && messages.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages yet. Send a message to start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: MessageWithParts,
    streamingPartTexts: Map<String, String>,
    onFileClick: (String) -> Unit
) {
    val isUser = message.info.isUser

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        var i = 0
        while (i < message.parts.size) {
            val part = message.parts[i]
            val streamingKey = "${message.info.id}:${part.id}"
            val streamingText = streamingPartTexts[streamingKey]
            val isToolLike = part.isTool || (part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty())
            if (isToolLike) {
                val run = mutableListOf<Part>()
                var j = i
                while (j < message.parts.size) {
                    val p = message.parts[j]
                    if (p.isTool || (p.isPatch && p.filePathsForNavigationFiltered.isNotEmpty())) {
                        run.add(p)
                        j++
                    } else break
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    run.forEach { p ->
                        PartView(
                            p,
                            isUser,
                            streamingPartTexts["${message.info.id}:${p.id}"],
                            onFileClick,
                            Modifier.weight(1f)
                        )
                    }
                    if (run.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                i = j
            } else {
                PartView(part, isUser, streamingText, onFileClick, Modifier.fillMaxWidth())
                i += 1
            }
        }
    }
}

@Composable
private fun PartView(
    part: Part,
    isUser: Boolean,
    streamingTextOverride: String?,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    when {
        part.isText -> {
            TextPart(
                text = streamingTextOverride ?: part.text ?: "",
                isUser = isUser,
                modifier = modifier
            )
        }
        part.isReasoning -> {
            ReasoningCard(
                text = streamingTextOverride ?: part.text ?: "",
                title = part.toolReason,
                isStreaming = false,
                modifier = modifier
            )
        }
        part.isTool -> {
            ToolCard(
                toolName = part.tool ?: "",
                status = part.stateDisplay,
                reason = part.toolReason,
                filePaths = part.filePathsForNavigationFiltered,
                todos = part.toolTodos,
                onFileClick = onFileClick,
                modifier = modifier
            )
        }
        part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty() -> {
            PatchCard(
                filePaths = part.filePathsForNavigationFiltered,
                onFileClick = onFileClick,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun TextPart(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val innerModifier = modifier.padding(12.dp)

    if (isUser) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = modifier
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Markdown(
                content = text,
                typography = markdownTypographyCompact(),
                modifier = innerModifier
            )
        }
    }
}

@Composable
private fun ReasoningCard(
    text: String,
    title: String?,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var expanded by remember { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming) {
        if (isStreaming) expanded = true
    }

    Card(
        modifier = modifier.padding(vertical = 4.dp),
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
                if (!isStreaming) {
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
            }
            if ((expanded || isStreaming) && text.isNotBlank()) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                    Markdown(
                        content = text,
                        typography = markdownTypographyCompact(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(
    toolName: String,
    status: String?,
    reason: String?,
    filePaths: List<String>,
    todos: List<com.yage.opencode_client.data.model.TodoItem> = emptyList(),
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val isRunning = status == "running"
    var expanded by remember { mutableStateOf(isRunning) }
    val firstFile = filePaths.firstOrNull()
    val isWriteOrPatch = toolName == "write" || toolName == "patch" || toolName.contains("write")
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isWriteOrPatch && isDark) ToolWritePatchBackgroundDark else MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = if (isWriteOrPatch && !isDark) MaterialTheme.colorScheme.primary else LocalContentColor.current

    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    text = toolName.ifEmpty { reason ?: "tool" },
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                if (firstFile != null) {
                    IconButton(
                        onClick = { onFileClick(firstFile) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Show in Files",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
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

            if (expanded && todos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                todos.forEach { todo ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (todo.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (todo.isCompleted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = todo.content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDecoration = if (todo.isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        if (todo.priority != "medium") {
                            Text(
                                text = todo.priority,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
            if (expanded && filePaths.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                filePaths.forEach { path ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onFileClick(path) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = "Show in Files",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PatchCard(
    filePaths: List<String>,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) ToolWritePatchBackgroundDark else MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = if (!isDark) MaterialTheme.colorScheme.primary else LocalContentColor.current

    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        )
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onFileClick(path) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Show in Files",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
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
