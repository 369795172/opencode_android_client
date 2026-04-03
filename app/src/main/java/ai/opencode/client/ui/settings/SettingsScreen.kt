package ai.opencode.client.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.opencode.client.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved = remember(viewModel) { viewModel.getSavedConnectionSettings() }
    val savedAIBuilder = remember(viewModel) { viewModel.getAIBuilderSettings() }

    var serverUrl by remember { mutableStateOf(saved.serverUrl) }
    var workspaceDirectory by remember { mutableStateOf(saved.workspaceDirectory) }
    var username by remember { mutableStateOf(saved.username) }
    var password by remember { mutableStateOf(saved.password) }
    var showPassword by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var aiBuilderBaseURL by remember { mutableStateOf(savedAIBuilder.baseURL) }
    var aiBuilderToken by remember { mutableStateOf(savedAIBuilder.token) }
    var aiBuilderCustomPrompt by remember { mutableStateOf(savedAIBuilder.customPrompt) }
    var aiBuilderTerminology by remember { mutableStateOf(savedAIBuilder.terminology) }
    var showAIBuilderToken by remember { mutableStateOf(false) }

    LaunchedEffect(state.isConnecting) {
        if (!state.isConnecting && isTesting) {
            isTesting = false
            testResult = TestResult(
                success = state.isConnected,
                message = if (state.isConnected) {
                    "Connected successfully" + (state.serverVersion?.let { " (v$it)" } ?: "")
                } else {
                    state.error ?: "Connection failed"
                }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (onBack != null) {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            ServerConnectionSection(
                serverUrl = serverUrl,
                workspaceDirectory = workspaceDirectory,
                username = username,
                password = password,
                showPassword = showPassword,
                isTesting = isTesting,
                state = state,
                testResult = testResult,
                onServerUrlChange = {
                    serverUrl = it
                    testResult = null
                },
                onWorkspaceDirectoryChange = {
                    workspaceDirectory = it
                    testResult = null
                },
                onUsernameChange = {
                    username = it
                    testResult = null
                },
                onPasswordChange = {
                    password = it
                    testResult = null
                },
                onTogglePasswordVisibility = { showPassword = !showPassword },
                onTestConnection = {
                    isTesting = true
                    testResult = null
                    viewModel.configureServer(
                        url = serverUrl,
                        workspaceDirectory = workspaceDirectory,
                        username = username.ifBlank { null },
                        password = password.ifBlank { null }
                    )
                    viewModel.testConnection()
                },
                onSave = {
                    viewModel.configureServer(
                        url = serverUrl,
                        workspaceDirectory = workspaceDirectory,
                        username = username.ifBlank { null },
                        password = password.ifBlank { null }
                    )
                    testResult = TestResult(success = true, message = "Settings saved")
                }
            )

            SettingsSectionDivider()

            AppearanceSection(
                themeMode = state.themeMode,
                onThemeSelected = viewModel::setThemeMode
            )

            SettingsSectionDivider()

            SpeechRecognitionSection(
                state = state,
                aiBuilderBaseURL = aiBuilderBaseURL,
                aiBuilderToken = aiBuilderToken,
                aiBuilderCustomPrompt = aiBuilderCustomPrompt,
                aiBuilderTerminology = aiBuilderTerminology,
                showAIBuilderToken = showAIBuilderToken,
                onBaseUrlChange = { aiBuilderBaseURL = it },
                onTokenChange = { aiBuilderToken = it },
                onPromptChange = { aiBuilderCustomPrompt = it },
                onTerminologyChange = { aiBuilderTerminology = it },
                onToggleTokenVisibility = { showAIBuilderToken = !showAIBuilderToken },
                onTestConnection = {
                    viewModel.saveAIBuilderSettings(
                        buildAIBuilderSettings(
                            baseURL = aiBuilderBaseURL,
                            token = aiBuilderToken,
                            customPrompt = aiBuilderCustomPrompt,
                            terminology = aiBuilderTerminology
                        )
                    )
                    viewModel.testAIBuilderConnection()
                },
                onSave = {
                    viewModel.saveAIBuilderSettings(
                        buildAIBuilderSettings(
                            baseURL = aiBuilderBaseURL,
                            token = aiBuilderToken,
                            customPrompt = aiBuilderCustomPrompt,
                            terminology = aiBuilderTerminology
                        )
                    )
                }
            )

            SettingsSectionDivider()

            AboutSection()
        }
    }
}
