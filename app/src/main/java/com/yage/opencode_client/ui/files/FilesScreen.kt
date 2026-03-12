package com.yage.opencode_client.ui.files

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mikepenz.markdown.m3.Markdown
import com.yage.opencode_client.data.model.FileContent
import com.yage.opencode_client.data.model.FileNode
import com.yage.opencode_client.data.model.FileStatusEntry
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.theme.AddedFile
import com.yage.opencode_client.ui.theme.DeletedFile
import com.yage.opencode_client.ui.theme.ModifiedFile
import com.yage.opencode_client.ui.theme.UntrackedFile
import com.yage.opencode_client.ui.theme.markdownTypographyCompact
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    repository: OpenCodeRepository,
    pathToShow: String? = null,
    sessionDirectory: String? = null,
    onCloseFile: () -> Unit = {},
    onFileClick: (String) -> Unit = {}
) {
    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var fileStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedFileContent by remember { mutableStateOf<FileContent?>(null) }
    var selectedFilePath by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    fun setDirectoryPreview(path: String, relPath: String, tree: List<FileNode>) {
        selectedFilePath = path
        selectedFileContent = FileContent(
            type = "text",
            content = if (tree.isEmpty()) {
                "Directory (empty or path not found): $relPath"
            } else {
                "Directory:\n" + tree.joinToString("\n") { it.path }
            }
        )
    }

    LaunchedEffect(pathToShow, sessionDirectory) {
        if (pathToShow == null) {
            selectedFilePath = null
            selectedFileContent = null
        } else {
            val sessionNorm = sessionDirectory?.trimStart('/') ?: ""
            val pathNorm = pathToShow.trimStart('/')
            val relPath = when {
                sessionNorm.isNotEmpty() && (pathNorm == sessionNorm || pathNorm.startsWith("$sessionNorm/")) ->
                    pathNorm.removePrefix(sessionNorm).trimStart('/')

                else -> pathNorm
            }
            repository.getFileContent(relPath)
                .onSuccess { content ->
                    if (!content.content.isNullOrBlank()) {
                        selectedFileContent = content
                        selectedFilePath = pathToShow
                    } else {
                        repository.getFileTree(relPath)
                            .onSuccess { tree -> setDirectoryPreview(pathToShow, relPath, tree) }
                            .onFailure { error = it.message }
                    }
                }
                .onFailure {
                    repository.getFileTree(relPath)
                        .onSuccess { tree -> setDirectoryPreview(pathToShow, relPath, tree) }
                        .onFailure { error = it.message }
                }
        }
    }

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
                    selectedFileContent = content
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
        if (selectedFilePath == null) {
            TopAppBar(
                title = { Text(currentPath.ifEmpty { "Files" }) },
                navigationIcon = {
                    if (currentPath.isNotEmpty()) {
                        IconButton(onClick = {
                            val parentPath = if ('/' in currentPath) {
                                currentPath.substringBeforeLast("/")
                            } else {
                                ""
                            }
                            currentPath = parentPath
                            loadFiles(parentPath)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        }

        error?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { error = null }) {
                        Text("Dismiss")
                    }
                }
            ) {
                Text(message)
            }
        }

        if (selectedFilePath != null && selectedFileContent != null) {
            FileContentViewer(
                path = selectedFilePath!!,
                fileContent = selectedFileContent!!,
                onClose = {
                    selectedFilePath = null
                    selectedFileContent = null
                    onCloseFile()
                }
            )
        } else if (isLoading) {
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
    fileContent: FileContent,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val content = fileContent.content.orEmpty()
    val isMarkdown = path.endsWith(".md", ignoreCase = true)
    val imagePayload = remember(path, content) {
        if (FilePreviewUtils.isImagePath(path)) decodeImagePayload(content) else null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleSmall
                )
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            },
            actions = {
                if (imagePayload != null) {
                    IconButton(onClick = { shareImage(context, path, imagePayload.bytes) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                }
            }
        )

        HorizontalDivider()

        when {
            imagePayload != null -> ImageViewer(bitmap = imagePayload.bitmap)

            isMarkdown -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    item {
                        Markdown(
                            content = content,
                            typography = markdownTypographyCompact(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            fileContent.isBinary -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Binary file preview is not supported.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            else -> {
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
}

@Composable
private fun ImageViewer(bitmap: Bitmap) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()
        val fitRatio = remember(containerWidth, containerHeight, bitmap.width, bitmap.height) {
            if (bitmap.width == 0 || bitmap.height == 0 || containerWidth == 0f || containerHeight == 0f) {
                1f
            } else {
                min(containerWidth / bitmap.width.toFloat(), containerHeight / bitmap.height.toFloat())
            }
        }
        val fittedWidth = bitmap.width * fitRatio
        val fittedHeight = bitmap.height * fitRatio
        val maxDoubleTapScale = remember(fitRatio) {
            (1f / fitRatio).coerceIn(2f, 5f)
        }

        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        fun clampOffset(candidate: Offset, targetScale: Float): Offset {
            val maxX = max(0f, (fittedWidth * targetScale - containerWidth) / 2f)
            val maxY = max(0f, (fittedHeight * targetScale - containerHeight) / 2f)
            return Offset(
                x = candidate.x.coerceIn(-maxX, maxX),
                y = candidate.y.coerceIn(-maxY, maxY)
            )
        }

        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
            scale = newScale
            offset = clampOffset(
                candidate = offset + if (newScale > 1f) panChange else Offset.Zero,
                targetScale = newScale
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transformState)
                .pointerInput(maxDoubleTapScale) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = maxDoubleTapScale
                                offset = Offset.Zero
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        }
    }
}

private data class DecodedImagePayload(
    val bytes: ByteArray,
    val bitmap: Bitmap
)

private fun decodeImagePayload(rawContent: String): DecodedImagePayload? {
    val candidates = listOf(
        rawContent,
        rawContent.replace("\n", "").replace("\r", "").replace(" ", "")
    ).distinct()

    for (candidate in candidates) {
        val bytes = try {
            Base64.decode(candidate, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            continue
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
        return DecodedImagePayload(bytes = bytes, bitmap = bitmap)
    }

    return null
}

private fun shareImage(context: Context, path: String, bytes: ByteArray) {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    val fileName = path.substringAfterLast('/').ifBlank { "image" }
    val shareFile = File(sharedDir, fileName)
    shareFile.writeBytes(bytes)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        shareFile
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = FilePreviewUtils.imageMimeType(path)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(
        Intent.createChooser(intent, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
