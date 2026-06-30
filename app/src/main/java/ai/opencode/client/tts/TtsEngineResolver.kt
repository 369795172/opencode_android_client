package ai.opencode.client.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.util.Locale

internal object TtsEngineResolver {

    private val preferredEnginePackages = listOf(
        "com.google.android.tts",
        "com.iflytek.speechcloud",
        "com.baidu.duersdk.opensdk",
        "com.oplus.ttsaccessibilityengine",
        "com.coloros.speechassist",
        "com.heytap.speechassist",
    )

    fun resolveEnginePackage(context: Context): String? {
        val installed = queryInstalledEnginePackages(context)
        if (installed.isEmpty()) return null
        val systemDefault = Settings.Secure.getString(
            context.contentResolver,
            "tts_default_synth"
        )
        if (!systemDefault.isNullOrBlank() && systemDefault in installed) {
            return systemDefault
        }
        preferredEnginePackages.firstOrNull { it in installed }?.let { return it }
        return installed.first()
    }

    fun configureLanguage(engine: TextToSpeech): Locale? {
        val candidates = listOf(
            Locale.getDefault(),
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.US,
        )
        for (locale in candidates) {
            when (engine.setLanguage(locale)) {
                TextToSpeech.LANG_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_AVAILABLE,
                TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> return locale
            }
        }
        return null
    }

    private fun queryInstalledEnginePackages(context: Context): List<String> {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        @Suppress("DEPRECATION")
        val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            pm.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return services.mapNotNull { it.serviceInfo?.packageName }.distinct()
    }
}
