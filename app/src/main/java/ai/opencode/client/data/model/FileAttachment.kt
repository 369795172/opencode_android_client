package ai.opencode.client.data.model

import android.net.Uri

data class FileAttachment(
    val uri: Uri,
    val filename: String,
    val mime: String,
    val sizeBytes: Long,
    val base64Content: String? = null
) {
    val isTextFile: Boolean
        get() = mime.startsWith("text/") ||
                mime in SUPPORTED_MIME_TYPES

    val isValidSize: Boolean
        get() = sizeBytes in 0..MAX_FILE_SIZE

    val displaySize: String
        get() = when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
            else -> "${sizeBytes / (1024 * 1024)}MB"
        }

    companion object {
        const val MAX_FILE_SIZE = 1_000_000L  // 1MB
        const val MAX_FILES_PER_MESSAGE = 5

        val SUPPORTED_MIME_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/yaml",
            "application/x-yaml",
            "application/toml"
        )

        val SUPPORTED_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "yaml", "yml",
            "xml", "toml", "log", "csv", "tsv",
            "kt", "java", "py", "js", "ts", "go", "rs", "c", "cpp", "h",
            "html", "css", "scss", "sql", "sh", "bash", "zsh",
            "env", "gitignore", "dockerignore", "editorconfig"
        )
    }
}
