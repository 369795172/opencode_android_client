package com.yage.opencode_client

import com.yage.opencode_client.data.model.QuestionRequest
import com.yage.opencode_client.ui.parseQuestionAskedEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `test parseQuestionAskedEvent with valid JSON`() {
        val questionJson = """
            {
                "id": "test-question-1",
                "sessionID": "test-session",
                "questions": [
                    {
                        "question": "What framework do you use?",
                        "header": "Framework Choice",
                        "options": [
                            {
                                "label": "React",
                                "description": "Popular UI library"
                            },
                            {
                                "label": "Vue",
                                "description": "Progressive framework"
                            }
                        ],
                        "multiple": false,
                        "custom": true
                    }
                ]
            }
        """.trimIndent()
        
        val event = SSEEvent(
            type = "question.asked",
            payload = json.parseToJsonElement(questionJson)
        )
        
        val result = parseQuestionAskedEvent(event)
        
        assertNotNull(result)
        assertEquals("test-question-1", result?.id)
        assertEquals("test-session", result?.sessionId)
        assertEquals(1, result?.questions.size)
        assertEquals("What framework do you use?", result?.questions[0].question)
    }

    @Test
    fun `test parseQuestionAskedEvent with empty questions`() {
        val questionJson = """
            {
                "id": "test-question-2",
                "sessionID": "test-session",
                "questions": []
            }
        """.trimIndent()
        
        val event = SSEEvent(
            type = "question.asked",
            payload = json.parseToJsonElement(questionJson)
        )
        
        val result = parseQuestionAskedEvent(event)
        
        assertNotNull(result)
        assertEquals(0, result?.questions.size)
    }

    @Test
    fun `test parseQuestionAskedEvent with invalid JSON returns null`() {
        val event = SSEEvent(
            type = "question.asked",
            payload = json.parseToJsonElement("invalid json")
        )
        
        val result = parseQuestionAskedEvent(event)
        
        assertNull(result)
    }
}
