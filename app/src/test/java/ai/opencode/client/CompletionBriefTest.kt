package ai.opencode.client

import ai.opencode.client.data.model.CompletionBrief
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity tests for the CompletionBrief contract against sanitized fixture
 * vectors in app/src/test/resources/completion_brief_vectors.json. Swapping
 * that file for the official vector set is sufficient to re-run parity.
 */
class CompletionBriefTest {
    @Test
    fun `matches every sanitized vector`() {
        val root = vectorsRoot()
        val cases = root["cases"]?.jsonArray ?: error("vectors file has no cases array")
        assertTrue("expected at least 8 vectors, got ${cases.size}", cases.size >= 8)
        cases.forEach { element ->
            val vector = element.jsonObject
            val name = vector.stringOf("name") ?: error("vector without name")
            val payloadJson = vector["session_payload"]!!.toString()
            val title = (vector["title"] as? JsonPrimitive)?.contentOrNull
            val expected = vector["expect"]!!.jsonObject
            val brief = CompletionBrief.extract(payloadJson, title)
            assertEquals("title mismatch in $name", expected.stringOf("title"), brief.title)
            assertEquals("brief mismatch in $name", expected.stringOf("brief"), brief.brief)
            assertEquals(
                "assistantMessageId mismatch in $name",
                expected.stringOf("assistant_message_id"),
                brief.assistantMessageId
            )
            assertEquals("dedupeKey mismatch in $name", expected.stringOf("dedupe_key"), brief.dedupeKey)
        }
    }

    @Test
    fun `code point caps never split surrogate pairs`() {
        val emojiTitle = "a".repeat(79) + "🚀"
        val payload = assistantPayload("ses_cap0001", "m_cap1", "ok")
        val brief = CompletionBrief.extract(payload, emojiTitle)
        assertEquals(80, brief.title.codePointCount(0, brief.title.length))
        assertEquals(emojiTitle, brief.title)
        // A pair split by naive UTF-16 truncation would leave an unpaired high surrogate at the end.
        assertFalse(Character.isHighSurrogate(brief.title.last()))
        assertEquals(0x1F680, brief.title.codePointAt(brief.title.length - 2))

        val emojiBriefText = "b".repeat(119) + "🚀" + "XY"
        val brief2 = CompletionBrief.extract(assistantPayload("ses_cap0002", "m_cap2", emojiBriefText), "t")
        assertEquals(120, brief2.brief.codePointCount(0, brief2.brief.length))
        assertEquals("b".repeat(119) + "🚀", brief2.brief)
    }

    @Test
    fun `bare message array has no session id and no dedupe key`() {
        val payload = """[{"info":{"id":"m1","role":"assistant"},"parts":[{"id":"p1","type":"text","text":"hi"}]}]"""
        val brief = CompletionBrief.extract(payload, "Title")
        assertEquals("hi", brief.brief)
        assertEquals("m1", brief.assistantMessageId)
        assertNull(brief.dedupeKey)
        assertEquals("Title", brief.title)
    }

    @Test
    fun `invalid json payload degrades to fallback title and empty selection`() {
        val brief = CompletionBrief.extract("not json", null)
        assertEquals("OpenCode task ", brief.title)
        assertEquals("OpenCode task ", brief.brief)
        assertNull(brief.assistantMessageId)
        assertNull(brief.dedupeKey)
    }

    @Test
    fun `session id shorter than six characters does not crash the fallback title`() {
        val brief = CompletionBrief.extract("""{"id":"abc","messages":[]}""", null)
        assertEquals("OpenCode task abc", brief.title)
    }

    @Test
    fun `assistant without any text part yields null id and title-only brief`() {
        val payload = """{"id":"ses_empty01","messages":[{"info":{"id":"m_tool","role":"assistant"},"parts":[{"id":"p1","type":"tool","tool":"bash"}]}]}"""
        val brief = CompletionBrief.extract(payload, "Only title")
        assertNull(brief.assistantMessageId)
        assertNull(brief.dedupeKey)
        assertEquals("Only title", brief.brief)
    }

    private fun vectorsRoot(): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("completion_brief_vectors.json")
        assertNotNull("completion_brief_vectors.json missing from test resources", stream)
        return stream!!.bufferedReader().use { it.readText() }.let { Json.parseToJsonElement(it).jsonObject }
    }

    private fun JsonObject.stringOf(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun assistantPayload(sessionId: String, messageId: String, text: String): String {
        val part = kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive("p1"))
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(text))
        }
        val message = kotlinx.serialization.json.buildJsonObject {
            put("info", kotlinx.serialization.json.buildJsonObject {
                put("id", JsonPrimitive(messageId))
                put("role", JsonPrimitive("assistant"))
            })
            put("parts", JsonArray(listOf(part)))
        }
        return kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive(sessionId))
            put("messages", JsonArray(listOf(message)))
        }.toString()
    }
}
