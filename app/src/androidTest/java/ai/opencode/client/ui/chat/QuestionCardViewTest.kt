package ai.opencode.client.ui.chat

import ai.opencode.client.data.model.QuestionInfo
import ai.opencode.client.data.model.QuestionOption
import ai.opencode.client.data.model.QuestionRequest
import ai.opencode.client.ui.theme.OpenCodeTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuestionCardViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longOptionList_showsDismissAndNext() {
        val options = (1..12).map { i ->
            QuestionOption(label = "Option $i", description = "")
        }
        val request = QuestionRequest(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                QuestionInfo(
                    question = "Pick one option",
                    header = "",
                    options = options,
                    multiple = false,
                    custom = false
                )
            )
        )

        composeRule.setContent {
            OpenCodeTheme(darkTheme = false) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                        QuestionCardView(
                            question = request,
                            onReply = { _, _ -> },
                            onReject = {}
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
        composeRule.onNodeWithText("Option 1").performClick()
        // Single-question card labels the primary action "Submit" (question_submit)
        composeRule.onNodeWithText("Submit").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun collapseToggle_hidesOptions_andExpandRestores() {
        val options = (1..3).map { i ->
            QuestionOption(label = "Option $i", description = "")
        }
        val request = QuestionRequest(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                QuestionInfo(
                    question = "Pick one option",
                    header = "",
                    options = options,
                    multiple = false,
                    custom = false
                )
            )
        )

        composeRule.setContent {
            OpenCodeTheme(darkTheme = false) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                        QuestionCardView(
                            question = request,
                            onReply = { _, _ -> },
                            onReject = {}
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Option 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Collapse question").performClick()
        composeRule.onNodeWithText("Option 1").assertDoesNotExist()
        composeRule.onNodeWithText("1 of 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Expand question").performClick()
        composeRule.onNodeWithText("Option 1").assertIsDisplayed()
    }

    @Test
    fun collapse_preservesSelectedAnswer() {
        val request = QuestionRequest(
            id = "q2",
            sessionId = "s1",
            questions = listOf(
                QuestionInfo(
                    question = "First question",
                    header = "",
                    options = listOf(
                        QuestionOption(label = "Option 1", description = ""),
                        QuestionOption(label = "Option 2", description = "")
                    ),
                    multiple = false,
                    custom = false
                ),
                QuestionInfo(
                    question = "Second question",
                    header = "",
                    options = listOf(
                        QuestionOption(label = "Option A", description = "")
                    ),
                    multiple = false,
                    custom = false
                )
            )
        )

        composeRule.setContent {
            OpenCodeTheme(darkTheme = false) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                        QuestionCardView(
                            question = request,
                            onReply = { _, _ -> },
                            onReject = {}
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Option 1").performClick()
        composeRule.onNodeWithContentDescription("Collapse question").performClick()
        composeRule.onNodeWithContentDescription("Expand question").performClick()
        composeRule.onNodeWithText("Next").assertIsEnabled()
    }
}
