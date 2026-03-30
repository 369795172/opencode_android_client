package ai.opencode.client.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TodoItem(
    val content: String,
    val status: String,
    val priority: String,
    val id: String
) {
    val isCompleted: Boolean
        get() = status == "completed" || status == "cancelled"
}
