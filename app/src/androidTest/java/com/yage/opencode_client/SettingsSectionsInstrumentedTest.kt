package com.yage.opencode_client

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.settings.SpeechRecognitionSection
import org.junit.Rule
import org.junit.Test

class SettingsSectionsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun speechSectionDisablesActionsWhenBaseUrlIsBlank() {
        composeRule.setContent {
            MaterialTheme {
                SpeechRecognitionSection(
                    state = AppState(),
                    aiBuilderBaseURL = "",
                    aiBuilderToken = "",
                    aiBuilderCustomPrompt = "",
                    aiBuilderTerminology = "",
                    showAIBuilderToken = false,
                    onBaseUrlChange = {},
                    onTokenChange = {},
                    onPromptChange = {},
                    onTerminologyChange = {},
                    onToggleTokenVisibility = {},
                    onTestConnection = {},
                    onSave = {}
                )
            }
        }

        composeRule.onNodeWithText("Test Connection").assertIsNotEnabled()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun speechSectionShowsSuccessResultAndEnablesActionsWhenConfigured() {
        composeRule.setContent {
            MaterialTheme {
                SpeechRecognitionSection(
                    state = AppState(aiBuilderConnectionOK = true),
                    aiBuilderBaseURL = "https://builder.example.com",
                    aiBuilderToken = "token",
                    aiBuilderCustomPrompt = "",
                    aiBuilderTerminology = "",
                    showAIBuilderToken = false,
                    onBaseUrlChange = {},
                    onTokenChange = {},
                    onPromptChange = {},
                    onTerminologyChange = {},
                    onToggleTokenVisibility = {},
                    onTestConnection = {},
                    onSave = {}
                )
            }
        }

        composeRule.onNodeWithText("Test Connection").assertIsEnabled()
        composeRule.onNodeWithText("Save").assertIsEnabled()
        composeRule.onNodeWithText("Connected successfully").assertIsDisplayed()
    }
}
