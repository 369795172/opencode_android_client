package ai.opencode.client.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.opencode.client.data.model.FileNode
import ai.opencode.client.ui.theme.AddedFile
import ai.opencode.client.ui.theme.DeletedFile
import ai.opencode.client.ui.theme.ModifiedFile
import ai.opencode.client.ui.theme.UntrackedFile

@Composable
internal fun FileBrowserPane(
    files: List<FileNode>,
    fileStatuses: Map<String, String>,
    onFileSelected: (FileNode) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(files, key = { it.path }) { file ->
            FileRow(
                file = file,
                status = fileStatuses[file.path],
                onClick = { onFileSelected(file) }
            )
        }
    }
}

@Composable
internal fun FileRow(
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
