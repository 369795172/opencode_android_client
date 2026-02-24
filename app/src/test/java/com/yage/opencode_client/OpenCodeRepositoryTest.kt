package com.yage.opencode_client

import com.yage.opencode_client.data.model.AgentInfo
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.repository.OpenCodeRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenCodeRepositoryTest {

    private val server = MockWebServer()
    private lateinit var repository: OpenCodeRepository

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Before
    fun setup() = runBlocking {
        server.start()
        repository = OpenCodeRepository()
        repository.configure(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `checkHealth returns success`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("""{"healthy": true, "version": "1.0.0"}""")
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.checkHealth()

        assertTrue(result.isSuccess)
        val health = result.getOrThrow()
        assertTrue(health.healthy)
        assertEquals("1.0.0", health.version)
    }

    @Test
    fun `checkHealth handles network error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.checkHealth()

        assertTrue(result.isFailure)
    }

    @Test
    fun `getSessions returns list`() = runBlocking {
        val sessions = listOf(
            Session(id = "s1", directory = "/project", title = "Test")
        )
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(sessions))
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.getSessions()

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("s1", list[0].id)
        assertEquals("/project", list[0].directory)
    }

    @Test
    fun `getAgents returns list`() = runBlocking {
        val agents = listOf(
            AgentInfo(
                name = "Build",
                mode = "primary",
                hidden = false
            )
        )
        server.enqueue(
            MockResponse()
                .setBody(json.encodeToString(agents))
                .setHeader("Content-Type", "application/json")
        )

        val result = repository.getAgents()

        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(1, list.size)
        assertEquals("Build", list[0].name)
    }
}
