package com.yage.opencode_client

import android.util.Log
import com.yage.opencode_client.data.audio.AudioRecorderManager
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.PermissionRequest
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.data.model.SSEEvent
import com.yage.opencode_client.data.model.SSEPayload
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.MainViewModel
import com.yage.opencode_client.ui.ModelPresets
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.ThemeMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var audioRecorderManager: AudioRecorderManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        audioRecorderManager = mockk(relaxed = true)

        every { settingsManager.serverUrl } returns "http://server.test"
        every { settingsManager.username } returns null
        every { settingsManager.password } returns null
        every { settingsManager.currentSessionId } returns null
        every { settingsManager.selectedModelIndex } returns 0
        every { settingsManager.selectedAgentName } returns null
        every { settingsManager.themeMode } returns ThemeMode.SYSTEM
        every { settingsManager.aiBuilderBaseURL } returns "https://space.ai-builders.com/backend"
        every { settingsManager.aiBuilderToken } returns ""
        every { settingsManager.aiBuilderCustomPrompt } returns ""
        every { settingsManager.aiBuilderTerminology } returns ""
        every { settingsManager.aiBuilderLastOKSignature } returns null
        every { settingsManager.aiBuilderLastOKTestedAt } returns 0L

        every { settingsManager.serverUrl = any() } just runs
        every { settingsManager.username = any() } just runs
        every { settingsManager.password = any() } just runs
        every { settingsManager.currentSessionId = any() } just runs
        every { settingsManager.selectedModelIndex = any() } just runs
        every { settingsManager.selectedAgentName = any() } just runs
        every { settingsManager.themeMode = any() } just runs
        every { settingsManager.aiBuilderBaseURL = any() } just runs
        every { settingsManager.aiBuilderToken = any() } just runs
        every { settingsManager.aiBuilderCustomPrompt = any() } just runs
        every { settingsManager.aiBuilderTerminology = any() } just runs
        every { settingsManager.aiBuilderLastOKSignature = any() } just runs
        every { settingsManager.aiBuilderLastOKTestedAt = any() } just runs

        every { repository.connectSSE() } returns emptyFlow()
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        coEvery { repository.getMessages(any(), any()) } returns Result.success(emptyList())
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
    }

    private fun createViewModel(): MainViewModel {
        return MainViewModel(repository, settingsManager, audioRecorderManager)
    }

    private fun updateState(viewModel: MainViewModel, transform: (AppState) -> AppState) {
        val field = MainViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as MutableStateFlow<AppState>
        flow.value = transform(flow.value)
    }

    private fun handleSse(viewModel: MainViewModel, event: SSEEvent) {
        val method = MainViewModel::class.java.getDeclaredMethod("handleSSEEvent", SSEEvent::class.java)
        method.isAccessible = true
        method.invoke(viewModel, event)
    }

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `init clamps saved model index and configures repository`() = runTest {
        every { settingsManager.selectedModelIndex } returns 999

        val viewModel = createViewModel()

        assertEquals(ModelPresets.list.lastIndex, viewModel.state.value.selectedModelIndex)
        verify { settingsManager.selectedModelIndex = ModelPresets.list.lastIndex }
        verify { repository.configure("http://server.test", null, null) }
    }

    @Test
    fun `init restores AI Builder connection when signature matches`() = runTest {
        val baseUrl = "https://builder.example.com"
        val token = "secret-token"
        every { settingsManager.aiBuilderBaseURL } returns baseUrl
        every { settingsManager.aiBuilderToken } returns token
        every { settingsManager.aiBuilderLastOKSignature } returns sha256("$baseUrl|$token")

        val viewModel = createViewModel()

        assertTrue(viewModel.state.value.aiBuilderConnectionOK)
    }

    @Test
    fun `sendMessage success clears input and uses selected preset model`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("  hello world  ")
        viewModel.selectAgent("review")
        viewModel.selectModel(1)

        viewModel.sendMessage()
        advanceUntilIdle()

        val selected = ModelPresets.list[1]
        coVerify {
            repository.sendMessage(
                "session-1",
                "hello world",
                "review",
                Message.ModelInfo(selected.providerId, selected.modelId)
            )
        }
        assertEquals("", viewModel.state.value.inputText)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `sendMessage failure keeps input and exposes error`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.failure(IllegalStateException("send failed"))

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        assertEquals("hello", viewModel.state.value.inputText)
        assertEquals("send failed", viewModel.state.value.error)
    }

    @Test
    fun `sendMessage still queues prompt when current session is busy`() = runTest {
        coEvery { repository.sendMessage(any(), any(), any(), any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        updateState(viewModel) {
            it.copy(
                inputText = "queue this next",
                sessionStatuses = it.sessionStatuses + ("session-1" to SessionStatus(type = "busy"))
            )
        }

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify {
            repository.sendMessage(
                "session-1",
                "queue this next",
                any(),
                any()
            )
        }
        assertEquals("", viewModel.state.value.inputText)
    }

    @Test
    fun `sendMessage ignores blank input`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectSession("session-1")
        advanceUntilIdle()
        viewModel.setInputText("   ")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any()) }
        assertEquals("   ", viewModel.state.value.inputText)
    }

    @Test
    fun `sendMessage ignores request when no session is selected`() = runTest {
        val viewModel = createViewModel()
        viewModel.setInputText("hello")

        viewModel.sendMessage()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.sendMessage(any(), any(), any(), any()) }
        assertEquals("hello", viewModel.state.value.inputText)
    }

    @Test
    fun `loadMessages updates selected agent and preset model from last assistant`() = runTest {
        val preset = ModelPresets.list[2]
        val messages = listOf(
            MessageWithParts(info = Message(id = "u1", role = "user")),
            MessageWithParts(
                info = Message(
                    id = "a1",
                    role = "assistant",
                    agent = "plan",
                    model = Message.ModelInfo(preset.providerId, preset.modelId)
                )
            )
        )
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)

        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        viewModel.loadMessages("session-1")
        advanceUntilIdle()

        assertEquals(messages, viewModel.state.value.messages)
        assertEquals("plan", viewModel.state.value.selectedAgentName)
        assertEquals(2, viewModel.state.value.selectedModelIndex)
    }

    @Test
    fun `toggleRecording shows token guidance when AI Builder token missing`() = runTest {
        val viewModel = createViewModel()

        viewModel.toggleRecording()

        assertEquals(
            "Speech recognition requires an AI Builder token. Configure it in Settings.",
            viewModel.state.value.speechError
        )
        assertFalse(viewModel.state.value.isRecording)
    }

    @Test
    fun `toggleRecording requires successful AI Builder connection before recording`() = runTest {
        every { settingsManager.aiBuilderToken } returns "token"
        val viewModel = createViewModel()

        viewModel.toggleRecording()

        assertEquals(
            "AI Builder connection test has not passed. Please test in Settings first.",
            viewModel.state.value.speechError
        )
        assertFalse(viewModel.state.value.isRecording)
    }

    @Test
    fun `toggleRecording handles missing file when stopping recording`() = runTest {
        every { settingsManager.aiBuilderToken } returns "token"
        every { audioRecorderManager.stop() } returns null
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(isRecording = true, aiBuilderConnectionOK = true, inputText = "draft") }

        viewModel.toggleRecording()

        assertFalse(viewModel.state.value.isRecording)
        assertFalse(viewModel.state.value.isTranscribing)
        assertEquals("Recording failed: no file", viewModel.state.value.speechError)
        assertEquals("draft", viewModel.state.value.inputText)
    }

    @Test
    fun `handleSSEEvent appends streaming reasoning delta for current session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(currentSessionId = "session-1") }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "part",
                            buildJsonObject {
                                put("messageID", JsonPrimitive("message-1"))
                                put("id", JsonPrimitive("part-1"))
                                put("type", JsonPrimitive("reasoning"))
                            }
                        )
                        put("delta", JsonPrimitive("thinking"))
                    }
                )
            )
        )

        assertEquals("thinking", viewModel.state.value.streamingPartTexts["message-1:part-1"])
        assertEquals("part-1", viewModel.state.value.streamingReasoningPart?.id)
    }

    @Test
    fun `handleSSEEvent session created prepends parsed session`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(sessions = listOf(com.yage.opencode_client.data.model.Session(id = "session-1", directory = "/tmp/old")))
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.created",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-2"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("New Session"))
                            }
                        )
                    }
                )
            )
        )

        assertEquals(listOf("session-2", "session-1"), viewModel.state.value.sessions.map { it.id })
    }

    @Test
    fun `handleSSEEvent missing delta clears streaming state and refreshes messages`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "a2", role = "assistant")))
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = mapOf("message-1:part-1" to "partial"),
                streamingReasoningPart = Part(id = "part-1", messageId = "message-1", sessionId = "session-1", type = "reasoning")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put("part", buildJsonObject { put("type", JsonPrimitive("reasoning")) })
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
        assertEquals(messages, viewModel.state.value.messages)
    }

    @Test
    fun `handleSSEEvent ignores message updates when no current session is selected`() = runTest {
        val viewModel = createViewModel()

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("part", buildJsonObject { put("type", JsonPrimitive("reasoning")) })
                        put("delta", JsonPrimitive("ignored"))
                    }
                )
            )
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
    }

    @Test
    fun `handleSSEEvent idle status clears streaming state and refreshes messages`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "a1", role = "assistant")))
        coEvery { repository.getMessages("session-1", 30) } returns Result.success(messages)
        val viewModel = createViewModel()
        updateState(viewModel) {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = mapOf("message-1:part-1" to "partial"),
                streamingReasoningPart = Part(id = "part-1", messageId = "message-1", sessionId = "session-1", type = "reasoning")
            )
        }

        handleSse(
            viewModel,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.status",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "status",
                            buildJsonObject {
                                put("type", JsonPrimitive("idle"))
                            }
                        )
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.streamingPartTexts.isEmpty())
        assertNull(viewModel.state.value.streamingReasoningPart)
        assertEquals(messages, viewModel.state.value.messages)
    }

    @Test
    fun `handleSSEEvent permission asked refreshes pending permissions`() = runTest {
        val permissions = listOf(
            PermissionRequest(id = "perm-1", sessionId = "session-1", permission = "file.read")
        )
        coEvery { repository.getPendingPermissions() } returns Result.success(permissions)
        val viewModel = createViewModel()

        handleSse(
            viewModel,
            SSEEvent(payload = SSEPayload(type = "permission.asked"))
        )
        advanceUntilIdle()

        assertEquals(permissions, viewModel.state.value.pendingPermissions)
    }

    @Test
    fun `clearSpeechError clears speech error state`() = runTest {
        val viewModel = createViewModel()
        updateState(viewModel) { it.copy(speechError = "bad mic") }

        viewModel.clearSpeechError()

        assertNull(viewModel.state.value.speechError)
    }

    @org.junit.After
    fun tearDown() {
        unmockkAll()
    }
}
