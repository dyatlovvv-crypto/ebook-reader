package ru.reader.app.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: Flow<ReaderSettings> = _settings.asStateFlow()

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString("theme", next.theme.name)
            .putString("ui_palette", next.uiPalette.name)
            .putFloat("font_size", next.fontSizeSp)
            .putFloat("line_height", next.lineHeight)
            .putBoolean("theme_onboarding_done", next.themeOnboardingDone)
            .apply()
        _settings.value = next
    }

    private fun read(): ReaderSettings {
        return ReaderSettings(
            theme = prefs.getString("theme", null)?.let {
                runCatching { ReaderTheme.valueOf(it) }.getOrDefault(ReaderTheme.LIGHT)
            } ?: ReaderTheme.LIGHT,
            uiPalette = prefs.getString("ui_palette", null)?.let {
                runCatching { UiPalette.valueOf(it) }.getOrDefault(UiPalette.SYSTEM)
            } ?: UiPalette.SYSTEM,
            fontSizeSp = prefs.getFloat("font_size", 18f),
            lineHeight = prefs.getFloat("line_height", 1.45f),
            themeOnboardingDone = prefs.getBoolean("theme_onboarding_done", false)
        )
    }

    companion object {
        fun isNightUi(context: Context, palette: UiPalette): Boolean = when (palette) {
            UiPalette.DARK -> true
            UiPalette.LIGHT -> false
            UiPalette.SYSTEM -> {
                val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                mask == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
