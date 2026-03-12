package com.yage.opencode_client.data.audio

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class TranscriptionResponse(
    val requestId: String,
    val text: String
)

private data class RealtimeSessionResponse(
    val sessionId: String,
    val wsUrl: String
)

object AIBuildersAudioClient {
    private const val TAG = "AIBuildersAudio"
    private const val TARGET_SAMPLE_RATE = 24_000
    private const val SEND_CHUNK_SIZE = 240_000
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"

    suspend fun testConnection(baseURL: String, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val client = buildHttpClient()
            val url = buildAPIURL(normalizedBaseURL(baseURL), "/v1/embeddings")
            val body = JSONObject().put("input", "ok").toString()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code >= 400) {
                    throw IOException("Connection test failed with status ${response.code}")
                }
                Log.d(TAG, "Connection test passed: ${response.code}")
            }
            Unit
        }
    }

    suspend fun transcribe(
        baseURL: String,
        token: String,
        pcmAudio: ByteArray,
        language: String? = null,
        prompt: String? = null,
        terms: String? = null,
        onPartialTranscript: ((String) -> Unit)? = null
    ): Result<TranscriptionResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val client = buildHttpClient()
            val normalizedBase = normalizedBaseURL(baseURL)

            val session = createRealtimeSession(
                client = client,
                baseURL = normalizedBase,
                token = token,
                language = language,
                prompt = prompt,
                terms = terms
            )
            Log.d(TAG, "Realtime session created: ${session.sessionId}")

            val websocketURL = realtimeWebSocketURL(normalizedBase, session.wsUrl)
            Log.d(TAG, "Realtime websocket URL: $websocketURL")

            streamPCMOverWebSocket(
                client = client,
                websocketURL = websocketURL,
                sessionId = session.sessionId,
                pcmAudio = pcmAudio,
                onPartialTranscript = onPartialTranscript
            )
        }
    }

    fun normalizedBaseURL(rawBaseURL: String): String {
        val trimmed = rawBaseURL.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return "https://$trimmed"
    }

    fun buildAPIURL(base: String, path: String): String {
        val relativePath = path.removePrefix("/")
        val baseUri = URI(base)
        var basePath = baseUri.path ?: ""
        if (basePath.isNotEmpty() && !basePath.endsWith("/")) {
            basePath += "/"
        }

        val baseForAppend = URI(
            baseUri.scheme,
            baseUri.authority,
            basePath,
            null,
            null
        )
        return baseForAppend.resolve(relativePath).toString()
    }

    fun realtimeWebSocketURL(baseURL: String, relativePath: String): String {
        val httpURL = URI(buildAPIURL(baseURL, relativePath))
        val webSocketScheme = when (httpURL.scheme) {
            "https" -> "wss"
            "http" -> "ws"
            else -> httpURL.scheme
        }

        return URI(
            webSocketScheme,
            httpURL.userInfo,
            httpURL.host,
            httpURL.port,
            httpURL.path,
            httpURL.query,
            httpURL.fragment
        ).toString()
    }

    private fun buildHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private fun createRealtimeSession(
        client: OkHttpClient,
        baseURL: String,
        token: String,
        language: String?,
        prompt: String?,
        terms: String?
    ): RealtimeSessionResponse {
        val url = buildAPIURL(baseURL, "/v1/audio/realtime/sessions")
        val payload = JSONObject()
            .put("vad", false)
            .put("silence_duration_ms", 1200)

        val normalizedLanguage = language?.trim().orEmpty()
        if (normalizedLanguage.isNotEmpty()) {
            payload.put("language", normalizedLanguage)
        }

        val normalizedPrompt = prompt?.trim().orEmpty()
        if (normalizedPrompt.isNotEmpty()) {
            payload.put("prompt", normalizedPrompt)
        }

        val termsArray = terms
            ?.split(",")
            ?.map { term -> term.trim() }
            ?.filter { term -> term.isNotEmpty() }
            .orEmpty()
        if (termsArray.isNotEmpty()) {
            val jsonTerms = JSONArray()
            termsArray.forEach { term -> jsonTerms.put(term) }
            payload.put("terms", jsonTerms)
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code >= 400) {
                throw IOException("Create session failed with status ${response.code}")
            }

            val bodyText = response.body?.string()
                ?: throw IOException("Create session returned empty body")
            val bodyJson = JSONObject(bodyText)
            val sessionId = bodyJson.getString("session_id")
            val wsUrl = bodyJson.getString("ws_url")

            return RealtimeSessionResponse(
                sessionId = sessionId,
                wsUrl = wsUrl
            )
        }
    }

    private suspend fun streamPCMOverWebSocket(
        client: OkHttpClient,
        websocketURL: String,
        sessionId: String,
        pcmAudio: ByteArray,
        onPartialTranscript: ((String) -> Unit)?
    ): TranscriptionResponse {
        val readySignal = CompletableDeferred<Unit>()
        val resultSignal = CompletableDeferred<TranscriptionResponse>()
        val closed = AtomicBoolean(false)
        val partialBuffer = StringBuilder()
        var finalText = ""

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = JSONObject(text)
                    when (event.optString("type")) {
                        "session_ready" -> {
                            Log.d(TAG, "WebSocket session ready")
                            if (!readySignal.isCompleted) {
                                readySignal.complete(Unit)
                            }
                        }

                        "transcript_delta" -> {
                            val delta = event.optString("text")
                            if (delta.isNotEmpty()) {
                                partialBuffer.append(delta)
                                val partialText = partialBuffer.toString()
                                Log.d(TAG, "Partial transcript received: ${partialText.length} chars")
                                onPartialTranscript?.invoke(partialText)
                            }
                        }

                        "transcript_completed" -> {
                            finalText = event.optString("text").trim()
                            if (finalText.isEmpty()) {
                                finalText = partialBuffer.toString().trim()
                            }
                            Log.d(TAG, "Transcript completed: ${finalText.length} chars")
                            webSocket.send("{\"type\":\"stop\"}")
                        }

                        "session_stopped" -> {
                            if (closed.compareAndSet(false, true)) {
                                Log.d(TAG, "WebSocket session stopped")
                                val textResult = if (finalText.isNotEmpty()) {
                                    finalText
                                } else {
                                    partialBuffer.toString().trim()
                                }
                                resultSignal.complete(
                                    TranscriptionResponse(
                                        requestId = sessionId,
                                        text = textResult
                                    )
                                )
                            }
                        }

                        "error" -> {
                            val message = event.optString("message").ifEmpty {
                                event.optString("code", "Unknown websocket error")
                            }
                            if (closed.compareAndSet(false, true)) {
                                resultSignal.completeExceptionally(IOException(message))
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (closed.compareAndSet(false, true)) {
                        resultSignal.completeExceptionally(error)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!readySignal.isCompleted) {
                    readySignal.completeExceptionally(t)
                }
                if (closed.compareAndSet(false, true)) {
                    resultSignal.completeExceptionally(t)
                }
            }
        }

        val request = Request.Builder().url(websocketURL).build()
        val webSocket = client.newWebSocket(request, listener)

        try {
            readySignal.await()
            val chunkCount = if (pcmAudio.isEmpty()) 0 else (pcmAudio.size + SEND_CHUNK_SIZE - 1) / SEND_CHUNK_SIZE
            Log.d(
                TAG,
                "Sending PCM audio: bytes=${pcmAudio.size}, chunks=$chunkCount, targetRate=$TARGET_SAMPLE_RATE"
            )
            var chunkStart = 0
            while (chunkStart < pcmAudio.size) {
                val chunkEnd = minOf(chunkStart + SEND_CHUNK_SIZE, pcmAudio.size)
                val sent = webSocket.send(
                    pcmAudio
                        .copyOfRange(chunkStart, chunkEnd)
                        .toByteString()
                )
                if (!sent) {
                    throw IOException("Failed to send audio chunk")
                }
                chunkStart = chunkEnd
            }

            if (!webSocket.send("{\"type\":\"commit\"}")) {
                throw IOException("Failed to send commit event")
            }
            Log.d(TAG, "Commit event sent")

            val result = resultSignal.await()
            webSocket.close(1000, "done")
            return result
        } catch (error: Exception) {
            webSocket.cancel()
            throw error
        }
    }
}
