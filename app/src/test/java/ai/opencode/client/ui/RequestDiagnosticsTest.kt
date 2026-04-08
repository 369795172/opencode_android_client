package ai.opencode.client.ui

import ai.opencode.client.data.model.AgentInfo
import ai.opencode.client.data.model.ConfigProvider
import ai.opencode.client.data.model.Message
import ai.opencode.client.data.model.ProviderModel
import ai.opencode.client.data.model.ProvidersResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestDiagnosticsTest {

    @Test
    fun `preflight rejects unknown model`() {
        val model = Message.ModelInfo(providerId = "openai", modelId = "gpt-5.4")
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf("gpt-4.1-mini" to ProviderModel(id = "gpt-4.1-mini", status = "active"))
                )
            )
        )
        val result = runSendPreflight(
            model = model,
            agentName = "build",
            providers = providers,
            agents = listOf(AgentInfo(name = "build", mode = "primary", native = true)),
            directory = "/tmp/work"
        )
        assertFalse(result.ok)
        assertEquals(RequestErrorCode.INVALID_MODEL, result.failure?.code)
    }

    @Test
    fun `preflight rejects missing directory for custom subagent`() {
        val model = Message.ModelInfo(providerId = "openai", modelId = "gpt-5.4")
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf("gpt-5.4" to ProviderModel(id = "gpt-5.4", status = "active"))
                )
            )
        )
        val result = runSendPreflight(
            model = model,
            agentName = "custom-subagent",
            providers = providers,
            agents = listOf(AgentInfo(name = "custom-subagent", mode = "subagent", native = false)),
            directory = null
        )
        assertFalse(result.ok)
        assertEquals(RequestErrorCode.MISSING_DIRECTORY, result.failure?.code)
    }

    @Test
    fun `preflight accepts valid model agent and directory`() {
        val model = Message.ModelInfo(providerId = "openai", modelId = "gpt-5.4")
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf("gpt-5.4" to ProviderModel(id = "gpt-5.4", status = "active"))
                )
            )
        )
        val result = runSendPreflight(
            model = model,
            agentName = "build",
            providers = providers,
            agents = listOf(AgentInfo(name = "build", mode = "primary", native = true)),
            directory = "/Users/me/proj"
        )
        assertTrue(result.ok)
    }

    @Test
    fun `diagnostics append is bounded and export includes entries`() {
        var entries = emptyList<RequestDiagnosticEntry>()
        repeat(150) { idx ->
            entries = appendDiagnostic(
                entries,
                RequestDiagnosticEntry(
                    sessionId = "ses1",
                    requestId = "req1",
                    phase = AsyncRequestPhase.RUNNING,
                    agent = "build",
                    providerId = "openai",
                    modelId = "gpt-5.4",
                    message = "tick-$idx"
                )
            )
        }
        assertEquals(120, entries.size)
        val report = toDiagnosticsReport(entries)
        assertTrue(report.contains("OpenCode Android Diagnostics"))
        assertTrue(report.contains("tick-149"))
    }
}
