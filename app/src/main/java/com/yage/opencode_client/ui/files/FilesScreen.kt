package com.yage.opencode_client.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.yage.opencode_client.data.model.FileNode
import com.yage.opencode_client.data.model.FileStatusEntry
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.theme.ModifiedFile
import com.yage.opencode_client.ui.theme.AddedFile
import com.yage.opencode_client.ui.theme.DeletedFile
import com.yage.opencode_client.ui.theme.UntrackedFile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    repository: OpenCodeRepository,
    onFileClick: (String) -> Unit = {}
) {
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var fileStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedFileContent by remember { mutableStateOf<String?>(null) }
    var selectedFilePath by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    fun loadFiles(path: String) {
        scope.launch {
            isLoading = true
            error = null
            repository.getFileTree(path.ifEmpty { null })
                .onSuccess { files = it }
                .onFailure { error = it.message }
            isLoading = false
        }
    }

    fun loadFileStatuses() {
        scope.launch {
            repository.getFileStatus()
                .onSuccess { statuses ->
                    fileStatuses = statuses.mapNotNull { entry ->
                        entry.path?.let { it to (entry.status ?: "untracked") }
                    }.toMap()
                }
        }
    }

    fun loadFileContent(path: String) {
        scope.launch {
            repository.getFileContent(path)
                .onSuccess { content ->
                    selectedFileContent = content.text
                    selectedFilePath = path
                }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(Unit) {
        loadFiles(currentPath)
        loadFileStatuses()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(currentPath.ifEmpty { "Files" }) },
            navigationIcon = {
                if (currentPath.isNotEmpty()) {
                    IconButton(onClick = {
                        val parentPath = currentPath.substringBeforeLast("/")
                        currentPath = parentPath
                        loadFiles(parentPath)
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                IconButton(onClick = {
                    loadFiles(currentPath)
                    loadFileStatuses()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        )

        if (error != null) {
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { error = null }) {
                        Text("Dismiss")
                    }
                }
            ) {
                Text(error!!)
            }
        }

        if (selectedFilePath != null && selectedFileContent != null) {
            FileContentViewer(
                path = selectedFilePath!!,
                content = selectedFileContent!!,
                onClose = {
                    selectedFilePath = null
                    selectedFileContent = null
                }
            )
        } else {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files, key = { it.path }) { file ->
                        FileRow(
                            file = file,
                            status = fileStatuses[file.path],
                            onClick = {
                                if (file.isDirectory) {
                                    currentPath = file.path
                                    loadFiles(file.path)
                                } else {
                                    loadFileContent(file.path)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: FileNode,
    status: String?,
    onClick: () -> Unit
) {
    val statusColor = when (status) {
        "added" -> AddedFile
        "modified" -> ModifiedFile
        "deleted" -> DeletedFile
        else -> if (status == "untracked") UntrackedFile else null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = statusColor ?: MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyLarge,
            color = statusColor ?: MaterialTheme.colorScheme.onSurface
        )
        if (file.ignored == true) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ignored",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileContentViewer(
    path: String,
    content: String,
    onClose: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    path.split("/").lastOrNull() ?: path,
                    style = MaterialTheme.typography.titleSmall
                )
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        )

        HorizontalDivider()

        val isMarkdown = path.endsWith(".md", ignoreCase = true)

        if (isMarkdown) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    Markdown(content = content, modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
