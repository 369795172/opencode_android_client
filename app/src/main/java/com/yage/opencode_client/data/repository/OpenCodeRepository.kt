package com.yage.opencode_client.data.repository

import com.yage.opencode_client.data.api.*
import com.yage.opencode_client.data.model.*
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenCodeRepository @Inject constructor() {
    private var baseUrl: String = DEFAULT_SERVER
    private var username: String? = null
    private var password: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .apply {
                        if (username != null && password != null) {
                            val credential = "$username:$password"
                            val encoded = Base64.getEncoder().encodeToString(credential.toByteArray())
                            header("Authorization", "Basic $encoded")
                        }
                    }
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val retrofit: Retrofit by lazy {
        val url = if (baseUrl.startsWith("http")) baseUrl else "http://$baseUrl"
        Retrofit.Builder()
            .baseUrl(url.trimEnd('/') + "/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    private val api: OpenCodeApi by lazy { retrofit.create(OpenCodeApi::class.java) }

    private val sseClient: SSEClient by lazy { SSEClient(okHttpClient) }

    fun configure(baseUrl: String, username: String? = null, password: String? = null) {
        this.baseUrl = baseUrl
        this.username = username
        this.password = password
    }

    suspend fun checkHealth(): Result<HealthResponse> = runCatching { api.getHealth() }

    suspend fun getSessions(): Result<List<Session>> = runCatching { api.getSessions() }

    suspend fun createSession(title: String? = null): Result<Session> = runCatching {
        api.createSession(CreateSessionRequest(title = title))
    }

    suspend fun updateSession(sessionId: String, title: String): Result<Session> = runCatching {
        api.updateSession(sessionId, UpdateSessionRequest(title))
    }

    suspend fun deleteSession(sessionId: String): Result<Unit> = runCatching {
        api.deleteSession(sessionId)
    }

    suspend fun getSessionStatus(): Result<Map<String, SessionStatus>> = runCatching {
        api.getSessionStatus()
    }

    suspend fun getMessages(sessionId: String, limit: Int? = null): Result<List<MessageWithParts>> =
        runCatching { api.getMessages(sessionId, limit) }

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String = "build",
        model: Message.ModelInfo? = null
    ): Result<Unit> = runCatching {
        val request = PromptRequest(
            parts = listOf(PromptRequest.PartInput(text = text)),
            agent = agent,
            model = model?.let { PromptRequest.ModelInput(it.providerId, it.modelId) }
        )
        api.promptAsync(sessionId, request)
    }

    suspend fun abortSession(sessionId: String): Result<Unit> = runCatching {
        api.abortSession(sessionId)
    }

    suspend fun getPendingPermissions(): Result<List<PermissionRequest>> = runCatching {
        api.getPendingPermissions()
    }

    suspend fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    ): Result<Unit> = runCatching {
        api.respondPermission(sessionId, permissionId, PermissionResponseRequest(response.value))
    }

    suspend fun getProviders(): Result<ProvidersResponse> = runCatching { api.getProviders() }

    suspend fun getAgents(): Result<List<AgentInfo>> = runCatching { api.getAgents() }

    suspend fun getSessionDiff(sessionId: String): Result<List<FileDiff>> = runCatching {
        api.getSessionDiff(sessionId)
    }

    suspend fun getSessionTodos(sessionId: String): Result<List<TodoItem>> = runCatching {
        api.getSessionTodos(sessionId)
    }

    suspend fun getFileTree(path: String? = null): Result<List<FileNode>> = runCatching {
        api.getFileTree(path)
    }

    suspend fun getFileContent(path: String): Result<FileContent> = runCatching {
        api.getFileContent(path)
    }

    suspend fun getFileStatus(): Result<List<FileStatusEntry>> = runCatching {
        api.getFileStatus()
    }

    suspend fun findFile(query: String, limit: Int = 50): Result<List<String>> = runCatching {
        api.findFile(query, limit)
    }

    fun connectSSE(): Flow<Result<SSEEvent>> = sseClient.connect(baseUrl, username, password)

    companion object {
        const val DEFAULT_SERVER = "http://127.0.0.1:4096"
    }
}
