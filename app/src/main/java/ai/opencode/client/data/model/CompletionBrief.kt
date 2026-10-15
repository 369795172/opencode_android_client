package ai.opencode.client.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Privacy-safe completion brief for a finished OpenCode session.
 *
 * Deterministic zero-token extractor implementing extraction spec S1-S10
 * shared with the notification pipeline's reference implementation; the
 * official sanitized vectors vendored at
 * app/src/test/resources/completion_brief_vectors.json are the behavioral
 * contract. Pipeline: newest assistant message with a non-empty text part
 * (its id retained, non-empty text parts joined with a newline); fenced code
 * blocks removed line-wise; per-line blockquote/heading/list markers, then
 * emphasis characters (~~ * _ `), then images, then links; file://, ~/, and
 * slash-prefixed path tokens replaced with "[path]"; http(s) URLs keep
 * scheme://host/path and lose query/fragment; whitespace runs collapsed with
 * a Unicode whitespace class equivalent to Python re \s; title capped at 80
 * and brief at 120 Unicode code points (never splitting surrogate pairs). The
 * brief falls back to the normalized title; an absent or normalizes-to-empty
 * title falls back to "OpenCode task " plus the last 6 code points of the
 * session id. Dedupe key is "<session id>:<assistant message id>" with an
 * empty segment after the colon when no qualifying assistant message exists;
 * null only when the payload carries no session id.
 *
 * Contract notes for the parity twin: [sessionPayload] is JSON - either a
 * bare message array or an object carrying the session id ("id"/"session_id"/
 * "sessionID") and a "messages" array; each message may nest role/parts under
 * "info" or carry them flat. Pure Kotlin, no Android framework dependency,
 * no network.
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
        private const val FENCE_MARKER = "```"

        // Python re \s on str: 09-0D, 1C-1F, 20, 85, A0, 1680, 2000-200A,
        // 2028, 2029, 202F, 205F, 3000. One shared class so markdown markers,
        // path/URL boundaries, and whitespace collapse agree with the
        // reference implementation on what counts as whitespace.
        private const val WHITESPACE_CLASS =
            "\\x09-\\x0D\\x1C-\\x20\\x85\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val WHITESPACE_RUN = Regex("[$WHITESPACE_CLASS]+")
        private val BLOCKQUOTE_MARKER = Regex("^[$WHITESPACE_CLASS]*>+")
        private val HEADING_MARKER = Regex("^[$WHITESPACE_CLASS]*#+")
        private val LIST_MARKER = Regex("^[$WHITESPACE_CLASS]*(?:[-*+]|\\d+\\.)[$WHITESPACE_CLASS]+")
        private val IMAGE_MD = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
        private val LINK_MD = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
        private val HTTP_URL = Regex("https?://[^$WHITESPACE_CLASS]+")
        private val FILE_URL = Regex("file://[^$WHITESPACE_CLASS]+")
        private val HOME_PATH = Regex("(?<![0-9A-Za-z_])~/[^$WHITESPACE_CLASS]*")
        private val ABSOLUTE_PATH = Regex("(?<![0-9A-Za-z_:/~.])/(?:[^$WHITESPACE_CLASS/]*/)+[^$WHITESPACE_CLASS]*")

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
                FALLBACK_TITLE_PREFIX + sessionId.orEmpty().takeLastCodePoints(6)
            } else {
                capCodePoints(normalizedTitle, TITLE_CAP_CODE_POINTS)
            }

            val normalizedBrief = normalize(assistantText)
            val resolvedBrief = if (normalizedBrief.isEmpty()) {
                resolvedTitle
            } else {
                capCodePoints(normalizedBrief, BRIEF_CAP_CODE_POINTS)
            }

            val dedupeKey = sessionId?.let { "$it:${assistantMessageId.orEmpty()}" }

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

        /** Newest assistant message whose non-blank text parts, joined by "\n", are non-empty. */
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
                val parts = (message["parts"] as? JsonArray)
                    ?: (info["parts"] as? JsonArray)
                    ?: continue
                val content = parts.mapNotNull { part ->
                    val partObj = part as? JsonObject ?: return@mapNotNull null
                    if ((partObj["type"] as? JsonPrimitive)?.content != "text") return@mapNotNull null
                    val text = (partObj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return@mapNotNull null
                    text.takeIf { it.isNotBlank() }
                }
                if (content.isNotEmpty()) {
                    val id = (info["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    return content.joinToString("\n") to id
                }
            }
            return "" to null
        }

        private fun normalize(raw: String): String {
            var text = removeFencedCodeBlocks(raw)
            text = stripMarkdown(text)
            text = FILE_URL.replace(text, PATH_TOKEN)
            text = HOME_PATH.replace(text, PATH_TOKEN)
            text = ABSOLUTE_PATH.replace(text, PATH_TOKEN)
            text = HTTP_URL.replace(text) { match -> stripQueryAndFragment(match.value) }
            return WHITESPACE_RUN.replace(text, " ").trim()
        }

        private fun removeFencedCodeBlocks(text: String): String {
            val kept = mutableListOf<String>()
            var inFence = false
            for (line in text.split('\n')) {
                if (inFence) {
                    if (line.startsWith(FENCE_MARKER)) {
                        inFence = false
                    }
                    continue
                }
                if (line.startsWith(FENCE_MARKER)) {
                    inFence = true
                    continue
                }
                kept.add(line)
            }
            return kept.joinToString("\n")
        }

        private fun stripMarkdown(text: String): String {
            val strippedLines = text.split('\n').map { line ->
                var result = BLOCKQUOTE_MARKER.replaceFirst(line, "")
                result = HEADING_MARKER.replaceFirst(result, "")
                result = LIST_MARKER.replaceFirst(result, "")
                result
            }
            var result = strippedLines.joinToString("\n")
            for (marker in listOf("~~", "*", "_", "`")) {
                result = result.replace(marker, "")
            }
            result = IMAGE_MD.replace(result, "")
            return LINK_MD.replace(result) { it.groupValues[1] }
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

        private fun String.takeLastCodePoints(count: Int): String {
            val total = codePointCount(0, length)
            if (total <= count) return this
            return substring(offsetByCodePoints(0, total - count))
        }
    }
}
