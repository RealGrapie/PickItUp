package com.grapie.pickitup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

enum class Language { SYSTEM, RU, EN }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** UI preferences of the companion (language, theme, seed colour), persisted. */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    val language = MutableStateFlow(Language.SYSTEM)
    val theme = MutableStateFlow(ThemeMode.SYSTEM)
    /** Pure black dark theme. */
    val amoled = MutableStateFlow(false)
    val seed = MutableStateFlow(SeedColor.OZON)
    /** ARGB of the user's own seed colour (used when [seed] is CUSTOM). */
    val customColor = MutableStateFlow(SeedColor.CUSTOM.argb)

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        language.value = runCatching { Language.valueOf(prefs.getString("language", null)!!) }.getOrDefault(Language.SYSTEM)
        val storedTheme = prefs.getString("theme", null)
        amoled.value = prefs.getBoolean("amoled", storedTheme == "AMOLED")  // old builds had an AMOLED theme
        theme.value = if (storedTheme == "AMOLED") ThemeMode.DARK
        else runCatching { ThemeMode.valueOf(storedTheme!!) }.getOrDefault(ThemeMode.SYSTEM)
        seed.value = runCatching { SeedColor.valueOf(prefs.getString("seed", null)!!) }.getOrDefault(SeedColor.OZON)
            .takeIf { it in SeedColor.offered } ?: SeedColor.OZON
        customColor.value = prefs.getLong("customColor", SeedColor.CUSTOM.argb)
    }

    /** The one-time battery optimization prompt was shown (first launch only). */
    var batteryPromptShown: Boolean
        get() = prefs.getBoolean("batteryPromptShown", false)
        set(v) = prefs.edit().putBoolean("batteryPromptShown", v).apply()

    fun setLanguage(l: Language) {
        prefs.edit().putString("language", l.name).apply()
        language.value = l
    }

    fun setTheme(t: ThemeMode) {
        prefs.edit().putString("theme", t.name).apply()
        theme.value = t
    }

    fun setAmoled(on: Boolean) {
        prefs.edit().putBoolean("amoled", on).apply()
        amoled.value = on
    }

    fun setCustomColor(argb: Long) {
        prefs.edit().putLong("customColor", argb).apply()
        customColor.value = argb
    }

    fun setSeed(c: SeedColor) {
        prefs.edit().putString("seed", c.name).apply()
        seed.value = c
    }

    /** Strings for the chosen language; "system" means Russian on a Russian-language phone, else English. */
    fun strings(lang: Language = language.value): Strings = when (lang) {
        Language.RU -> Strings.RU
        Language.EN -> Strings.EN
        Language.SYSTEM -> if (Locale.getDefault().language == "ru") Strings.RU else Strings.EN
    }
}
