package ai.opencode.client.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.rokid.os.sprite.tts.ITtsListener
import com.rokid.os.sprite.tts.ITtsServer

/**
 * Client for the Rokid assistserver TTS service.
 *
 * Rokid glasses ship without any standard Android TTS engine
 * (`TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE` resolves to nothing), but the
 * vendor [com.rokid.os.sprite.assistserver] package runs a resident TTS service
 * exposing the binder AIDL [ITtsServer]. This engine binds it explicitly and
 * maps utterance tags onto [onUtteranceStart]/[onUtteranceDone] so [TtsService]
 * can reuse its chunk pipeline unchanged.
 *
 * Bind contract (verified from the vendor TtsServerManager bytecode):
 *  - explicit ComponentName on com.rokid.os.sprite.assistserver/.tts.TtsService
 *  - playTtsMsg(text, tag, listener): text required, tag is the dedup/stop key
 *  - stopTtsPlay(tag) cancels the utterance started with the same tag
 */
class RokidAssistTtsEngine(private val context: Context) {

    /** Invoked on a binder pool thread when the server starts the utterance. */
    var onUtteranceStart: ((tag: String) -> Unit)? = null

    /** Invoked on a binder pool thread when the utterance finishes or is stopped. */
    var onUtteranceDone: ((tag: String) -> Unit)? = null

    private var server: ITtsServer? = null
    private var connecting = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingReadyCallbacks = mutableListOf<() -> Unit>()

    private val binderCallbacks = object : ITtsListener.Stub() {
        override fun onTtsStart(tag: String?) {
            if (tag == null) return
            Log.d(TAG, "onTtsStart tag=$tag")
            onUtteranceStart?.invoke(tag)
        }

        override fun onTtsStop(tag: String?) {
            if (tag == null) return
            Log.d(TAG, "onTtsStop tag=$tag")
            onUtteranceDone?.invoke(tag)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            server = ITtsServer.Stub.asInterface(service)
            connecting = false
            Log.i(TAG, "assistserver TTS connected")
            val callbacks = synchronized(pendingReadyCallbacks) {
                val copy = pendingReadyCallbacks.toList()
                pendingReadyCallbacks.clear()
                copy
            }
            mainHandler.post {
                callbacks.forEach { it() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            server = null
            Log.w(TAG, "assistserver TTS disconnected; will rebind on next use")
        }
    }

    val isReady: Boolean get() = server != null

    /**
     * Ensure the service is bound; [onReady] runs on the main thread once the
     * binder is live (immediately if already connected). Returns false
     * synchronously when the package cannot be bound at all ("not a Rokid
     * device") — in that case queued callbacks are dropped and the caller
     * must treat the engine as unavailable.
     */
    fun ensureBound(onReady: () -> Unit): Boolean {
        server?.let {
            mainHandler.post(onReady)
            return true
        }
        if (connecting) return true
        connecting = true
        val intent = Intent().setComponent(TTS_COMPONENT)
        return try {
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (ok) {
                synchronized(pendingReadyCallbacks) { pendingReadyCallbacks.add(onReady) }
            } else {
                connecting = false
                Log.w(TAG, "bindService returned false (assistserver not installed?)")
            }
            ok
        } catch (e: Exception) {
            connecting = false
            Log.e(TAG, "bindService failed", e)
            false
        }
    }

    /**
     * Speak [text] under [tag]. Returns false when the engine is not connected
     * (caller should treat it as an utterance error and let its watchdog/retry
     * policy take over).
     */
    fun speak(text: String, tag: String): Boolean {
        if (text.isEmpty()) return false
        val svc = server ?: return false
        return try {
            svc.playTtsMsg(text, tag, binderCallbacks)
            Log.d(TAG, "playTtsMsg tag=$tag len=${text.length}")
            true
        } catch (e: Exception) {
            server = null
            Log.e(TAG, "playTtsMsg failed tag=$tag", e)
            false
        }
    }

    fun stop(tag: String) {
        val svc = server ?: return
        try {
            svc.stopTtsPlay(tag)
        } catch (e: Exception) {
            Log.w(TAG, "stopTtsPlay failed tag=$tag", e)
        }
    }

    fun release() {
        server = null
        connecting = false
        runCatching { context.unbindService(connection) }
            .onFailure { Log.d(TAG, "unbindService: ${it.message}") }
    }

    companion object {
        private const val TAG = "RokidAssistTts"

        /** The vendor TTS entry point inside the assistserver package. */
        val TTS_COMPONENT = ComponentName(
            "com.rokid.os.sprite.assistserver",
            "com.rokid.os.sprite.tts.TtsService",
        )
    }
}
