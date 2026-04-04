package ai.opencode.client.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import ai.opencode.client.data.model.FileAttachment
import java.io.InputStream

object FileEncoder {
    suspend fun loadAttachment(context: Context, uri: Uri): Result<FileAttachment> = runCatching {
        val resolver = context.contentResolver
        
        val filename = queryFilename(resolver, uri)
            ?: uri.lastPathSegment
            ?: "unknown"
        
        val mime = resolver.getType(uri)
            ?: guessMimeFromFilename(filename)
            ?: "text/plain"
        
        val size = querySize(resolver, uri)
        
        val base64 = loadAndEncode(resolver.openInputStream(uri))
        
        FileAttachment(
            uri = uri,
            filename = filename,
            mime = mime,
            sizeBytes = size,
            base64Content = base64
        )
    }

    private fun queryFilename(resolver: android.content.ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return null
    }

    private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getLong(idx)
            }
        }
        return 0L
    }

    private fun loadAndEncode(stream: InputStream?): String {
        stream?.use { input ->
            val bytes = input.readBytes()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        return ""
    }

    private fun guessMimeFromFilename(filename: String): String? {
        val ext = filename.substringAfterLast('.').lowercase()
        return EXT_TO_MIME[ext]
    }

    private val EXT_TO_MIME = mapOf(
        "txt" to "text/plain",
        "md" to "text/markdown",
        "markdown" to "text/markdown",
        "json" to "application/json",
        "yaml" to "application/yaml",
        "yml" to "application/yaml",
        "xml" to "application/xml",
        "toml" to "application/toml",
        "log" to "text/plain",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "kt" to "text/kotlin",
        "java" to "text/java",
        "py" to "text/x-python",
        "js" to "text/javascript",
        "ts" to "text/typescript",
        "go" to "text/go",
        "rs" to "text/rust",
        "c" to "text/c",
        "cpp" to "text/cpp",
        "h" to "text/c",
        "html" to "text/html",
        "css" to "text/css",
        "scss" to "text/x-scss",
        "sql" to "application/sql",
        "sh" to "text/x-sh",
        "bash" to "text/x-sh",
        "zsh" to "text/x-sh",
        "env" to "text/plain",
        "gitignore" to "text/plain",
        "dockerignore" to "text/plain",
        "editorconfig" to "text/plain"
    )
}
