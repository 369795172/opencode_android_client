package ai.opencode.client.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLocaleController {
    fun apply(mode: LanguageMode) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(mode.languageTag))
    }
}
