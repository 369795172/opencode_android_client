package com.yage.opencode_client.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.ui.AIBuilderSettings
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.util.ThemeMode
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved = remember { viewModel.getSavedConnectionSettings() }
    val savedAIBuilder = remember { viewModel.getAIBuilderSettings() }

    var serverUrl by remember { mutableStateOf(saved.serverUrl) }
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

    // Update test result when connection test completes
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
        Text(
            "Server Connection",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = {
                serverUrl = it
                testResult = null
            },
            label = { Text("Server URL") },
            placeholder = { Text("http://localhost:4096") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Cloud, contentDescription = null)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = {
                username = it
                testResult = null
            },
            label = { Text("Username (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Person, contentDescription = null)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                testResult = null
            },
            label = { Text("Password (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password"
                    )
                }
            },
            leadingIcon = {
                Icon(Icons.Default.Lock, contentDescription = null)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    isTesting = true
                    testResult = null
                    viewModel.configureServer(
                        url = serverUrl,
                        username = username.ifBlank { null },
                        password = password.ifBlank { null }
                    )
                    viewModel.testConnection()
                },
                enabled = serverUrl.isNotBlank() && !isTesting
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Test Connection")
            }

            OutlinedButton(
                onClick = {
                    viewModel.configureServer(
                        url = serverUrl,
                        username = username.ifBlank { null },
                        password = password.ifBlank { null }
                    )
                    testResult = TestResult(success = true, message = "Settings saved")
                },
                enabled = serverUrl.isNotBlank()
            ) {
                Text("Save")
            }
        }

        testResult?.let { result ->
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (result.success)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (result.success) Icons.Default.Check else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (result.success)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        result.message,
                        color = if (result.success)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (state.isConnected) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                state.serverVersion?.let { version ->
                    Text(
                        " (v$version)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "Appearance",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column {
            ThemeMode.values().forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when (mode) {
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                            ThemeMode.SYSTEM -> "System default"
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "Speech Recognition",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = aiBuilderBaseURL,
            onValueChange = { aiBuilderBaseURL = it },
            label = { Text("AI Builder Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Cloud, contentDescription = null)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = aiBuilderToken,
            onValueChange = { aiBuilderToken = it },
            label = { Text("AI Builder Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showAIBuilderToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showAIBuilderToken = !showAIBuilderToken }) {
                    Icon(
                        if (showAIBuilderToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showAIBuilderToken) "Hide token" else "Show token"
                    )
                }
            },
            leadingIcon = {
                Icon(Icons.Default.Lock, contentDescription = null)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = aiBuilderCustomPrompt,
            onValueChange = { aiBuilderCustomPrompt = it },
            label = { Text("Custom Prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = aiBuilderTerminology,
            onValueChange = { aiBuilderTerminology = it },
            label = { Text("Terminology") },
            placeholder = { Text("comma-separated terms") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    viewModel.saveAIBuilderSettings(
                        AIBuilderSettings(
                            baseURL = aiBuilderBaseURL,
                            token = aiBuilderToken,
                            customPrompt = aiBuilderCustomPrompt,
                            terminology = aiBuilderTerminology
                        )
                    )
                    viewModel.testAIBuilderConnection()
                },
                enabled = aiBuilderBaseURL.isNotBlank() && !state.isTestingAIBuilderConnection
            ) {
                if (state.isTestingAIBuilderConnection) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Test Connection")
            }

            OutlinedButton(
                onClick = {
                    viewModel.saveAIBuilderSettings(
                        AIBuilderSettings(
                            baseURL = aiBuilderBaseURL,
                            token = aiBuilderToken,
                            customPrompt = aiBuilderCustomPrompt,
                            terminology = aiBuilderTerminology
                        )
                    )
                },
                enabled = aiBuilderBaseURL.isNotBlank()
            ) {
                Text("Save")
            }
        }

        if (state.aiBuilderConnectionOK || state.aiBuilderConnectionError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state.aiBuilderConnectionOK)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val success = state.aiBuilderConnectionOK
                    Icon(
                        if (success) Icons.Default.Check else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (success)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (success) "Connected successfully" else (state.aiBuilderConnectionError ?: "Connection failed"),
                        color = if (success)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "About",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "OpenCode Android Client",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "Version 1.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "A native Android client for OpenCode AI coding agent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        }
    }
}

private data class TestResult(
    val success: Boolean,
    val message: String
)
