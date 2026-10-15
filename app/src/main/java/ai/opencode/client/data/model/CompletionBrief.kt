package ai.opencode.client.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Privacy-safe completion brief for a finished OpenCode session.
 *
 * Deterministic zero-token contract mirrored from the notification pipeline
 * twin. Steps: newest assistant message with a non-empty text part (reasoning
 * and tool-only parts ignored, its id retained); fenced code blocks removed;
 * Markdown presentation stripped; absolute/home/`file://` paths replaced with
 * "[path]"; http(s) URLs keep scheme://host/path and lose query/fragment;
 * whitespace collapsed; title capped at 80 and brief at 120 Unicode code
 * points. The brief falls back to the normalized title; an absent or
 * normalizes-to-empty title falls back to "OpenCode task " plus the last 6
 * characters of the session id. Dedupe key is "<session id>:<assistant message
 * id>", null when either id is missing.
 *
 * Contract notes for the parity twin: [sessionPayload] is JSON - either a bare
 * message array or an object carrying the session id ("id"/"session_id"/
 * "sessionID") and a "messages" array; each message may nest role/parts under
 * "info" or carry them flat. URL stripping runs before path redaction so
 * path-looking query strings are removed first. A fence is a line whose
 * leading-whitespace-stripped form starts with "```". Pure Kotlin, no Android
 * framework dependency, no network.
 */
data class CompletionBrief(
    val title: String,
    val brief: String,
    val assistantMessageId: String?,
    val dedupeKey: String?
) {
    companion object {
        const val TITLE_CAP_CODE_POINTS = 80
        const val BRIEF_CAP_CODE_POINTS = 120
        private const val PATH_TOKEN = "[path]"
        private const val FALLBACK_TITLE_PREFIX = "OpenCode task "

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val IMAGE_REGEX = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
        private val LINK_REGEX = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
        private val URL_REGEX = Regex("https?://\\S+")
        private val FILE_URL_REGEX = Regex("file://\\S+")
        private val HOME_PATH_REGEX = Regex("(?<![0-9A-Za-z_])~/\\S*")
        private val ABS_PATH_REGEX = Regex("(?<![0-9A-Za-z_:/~.])/(?:[^\\s/]*/)+\\S*")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val BLOCKQUOTE_REGEX = Regex("^[ \\t]*>+[ \\t]*")
        private val HEADING_REGEX = Regex("^[ \\t]*#+[ \\t]*")
        private val LIST_BULLET_REGEX = Regex("^[ \\t]*[-*+][ \\t]+")
        private val LIST_ORDERED_REGEX = Regex("^[ \\t]*\\d+\\.[ \\t]+")

        /** Parses the JSON payload; invalid JSON degrades to an empty payload. */
        fun extract(sessionPayload: String, title: String?): CompletionBrief {
            val element = try {
                json.parseToJsonElement(sessionPayload)
            } catch (_: IllegalArgumentException) {
                null
            }
            return extract(element, title)
        }

        fun extract(sessionPayload: JsonElement?, title: String?): CompletionBrief {
            val sessionId = sessionPayload.sessionIdOrNull()
            val (assistantText, assistantMessageId) = latestAssistantText(sessionPayload)

            val normalizedTitle = normalize(title.orEmpty())
            val resolvedTitle = if (normalizedTitle.isEmpty()) {
                FALLBACK_TITLE_PREFIX + (sessionId?.let { it.substring(maxOf(0, it.length - 6)) } ?: "")
            } else {
                capCodePoints(normalizedTitle, TITLE_CAP_CODE_POINTS)
            }

            val normalizedBrief = normalize(assistantText)
            val resolvedBrief = if (normalizedBrief.isEmpty()) {
                resolvedTitle
            } else {
                capCodePoints(normalizedBrief, BRIEF_CAP_CODE_POINTS)
            }

            val dedupeKey = if (sessionId != null && assistantMessageId != null) {
                "$sessionId:$assistantMessageId"
            } else null

            return CompletionBrief(resolvedTitle, resolvedBrief, assistantMessageId, dedupeKey)
        }

        private fun JsonElement?.sessionIdOrNull(): String? {
            val obj = this as? JsonObject ?: return null
            for (key in listOf("id", "session_id", "sessionID")) {
                val value = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (value != null) return value
            }
            return null
        }

        /** Newest assistant message whose concatenated text parts strip to non-empty. */
        private fun latestAssistantText(payload: JsonElement?): Pair<String, String?> {
            val messages = when (payload) {
                is JsonArray -> payload
                is JsonObject -> (payload["messages"] as? JsonArray) ?: return "" to null
                else -> return "" to null
            }
            for (index in messages.indices.reversed()) {
                val message = messages[index] as? JsonObject ?: continue
                val info = message["info"] as? JsonObject ?: message
                if ((info["role"] as? JsonPrimitive)?.content != "assistant") continue
                val parts = (message["parts"] as? JsonArray)?.takeIf { it.isNotEmpty() }
                    ?: (info["parts"] as? JsonArray)?.takeIf { it.isNotEmpty() }
                    ?: continue
                val text = parts.joinToString("") { part ->
                    val partObj = part as? JsonObject ?: return@joinToString ""
                    val type = (partObj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (type != null && type != "text") return@joinToString ""
                    (partObj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
                }.trim()
                if (text.isNotEmpty()) {
                    return text to ((info["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
                }
            }
            return "" to null
        }

        private fun normalize(raw: String): String {
            val unfenced = removeFencedCodeBlocks(raw)
            val presentationStripped = unfenced.split('\n').joinToString("\n") { stripLinePresentation(it) }
            var text = IMAGE_REGEX.replace(presentationStripped, "")
            text = LINK_REGEX.replace(text) { it.groupValues[1] }
            for (marker in listOf("**", "__", "~~", "`", "*", "_")) {
                text = text.replace(marker, "")
            }
            text = URL_REGEX.replace(text) { match -> stripQueryAndFragment(match.value) }
            text = FILE_URL_REGEX.replace(text, PATH_TOKEN)
            text = HOME_PATH_REGEX.replace(text, PATH_TOKEN)
            text = ABS_PATH_REGEX.replace(text, PATH_TOKEN)
            return WHITESPACE_REGEX.replace(text, " ").trim()
        }

        private fun removeFencedCodeBlocks(text: String): String {
            val kept = mutableListOf<String>()
            var inFence = false
            for (line in text.split('\n')) {
                if (line.trimStart().startsWith("```")) {
                    inFence = !inFence
                } else if (!inFence) {
                    kept.add(line)
                }
            }
            return kept.joinToString("\n")
        }

        private fun stripLinePresentation(line: String): String {
            var result = BLOCKQUOTE_REGEX.replaceFirst(line, "")
            result = HEADING_REGEX.replaceFirst(result, "")
            result = LIST_BULLET_REGEX.replaceFirst(result, "")
            result = LIST_ORDERED_REGEX.replaceFirst(result, "")
            return result
        }

        private fun stripQueryAndFragment(url: String): String {
            var end = url.length
            val query = url.indexOf('?')
            if (query >= 0) end = minOf(end, query)
            val fragment = url.indexOf('#')
            if (fragment >= 0) end = minOf(end, fragment)
            return url.substring(0, end)
        }

        private fun capCodePoints(text: String, limit: Int): String {
            if (text.codePointCount(0, text.length) <= limit) return text
            return text.substring(0, text.offsetByCodePoints(0, limit))
        }
    }
}
