package ai.opencode.client.tts

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech

object TtsSystemSettings {

    fun openTtsSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            Intent("com.android.settings.TTS_SETTINGS").apply {
                setPackage("com.android.settings")
            },
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        for (intent in intents) {
            if (intent.resolveActivity(context.packageManager) != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            }
        }
        return false
    }
}
