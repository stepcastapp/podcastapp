package com.stepcast.app.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stepcast.app.data.prefsWriteScope
import com.stepcast.app.data.settingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AccentColor(val label: String) {
    CYAN("Neon Cyan"), // the brand default
    GREEN("Neon Green"),
    ORANGE("Signal Orange"),
    VIOLET("Violet"),
    GOLD("Gold"),
    PINK("Hot Pink"),
    DYNAMIC("Material You"), // wallpaper colors, Android 12+
    CUSTOM("Custom") // user-picked from the color wheel
}

/**
 * Persisted theme choices (dark/light override, primary accent, support
 * accent, custom wheel colors), exposed as Compose state so the whole tree
 * re-themes instantly. Call [init] once at startup.
 */
object ThemePrefs {

    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    var accent by mutableStateOf(AccentColor.CYAN)
        private set

    /** ARGB of the wheel-picked color backing [AccentColor.CUSTOM]. */
    var customAccentArgb by mutableStateOf(0xFF4AE3FF.toInt())
        private set

    /** Support (tertiary) accent; null = Auto, the pairing baked into the primary. */
    var secondaryAccent by mutableStateOf<AccentColor?>(null)
        private set

    /** ARGB backing a CUSTOM secondary accent. */
    var customSecondaryArgb by mutableStateOf(0xFFCFBCFF.toInt())
        private set

    fun init(context: Context) {
        val p = runBlocking { context.applicationContext.settingsStore.data.first() }
        mode = p[stringPreferencesKey(KEY_MODE)]
            ?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull() }
            ?: ThemeMode.SYSTEM
        accent = p[stringPreferencesKey(KEY_ACCENT)]
            ?.let { stored -> runCatching { AccentColor.valueOf(stored) }.getOrNull() }
            ?: AccentColor.CYAN
        customAccentArgb = p[intPreferencesKey(KEY_CUSTOM)] ?: 0xFF4AE3FF.toInt()
        secondaryAccent = p[stringPreferencesKey(KEY_SECONDARY)]
            ?.let { stored -> runCatching { AccentColor.valueOf(stored) }.getOrNull() }
        customSecondaryArgb =
            p[intPreferencesKey(KEY_CUSTOM_SECONDARY)] ?: 0xFFCFBCFF.toInt()
    }

    fun set(context: Context, newMode: ThemeMode) {
        mode = newMode
        putString(context, KEY_MODE, newMode.name)
    }

    fun setAccent(context: Context, newAccent: AccentColor) {
        accent = newAccent
        putString(context, KEY_ACCENT, newAccent.name)
    }

    fun setCustomAccent(context: Context, argb: Int) {
        customAccentArgb = argb
        accent = AccentColor.CUSTOM
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit {
                it[intPreferencesKey(KEY_CUSTOM)] = argb
                it[stringPreferencesKey(KEY_ACCENT)] = AccentColor.CUSTOM.name
            }
        }
    }

    fun setSecondaryAccent(context: Context, newAccent: AccentColor?) {
        secondaryAccent = newAccent
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit {
                val key = stringPreferencesKey(KEY_SECONDARY)
                if (newAccent == null) it.remove(key) else it[key] = newAccent.name
            }
        }
    }

    fun setCustomSecondaryAccent(context: Context, argb: Int) {
        customSecondaryArgb = argb
        secondaryAccent = AccentColor.CUSTOM
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit {
                it[intPreferencesKey(KEY_CUSTOM_SECONDARY)] = argb
                it[stringPreferencesKey(KEY_SECONDARY)] = AccentColor.CUSTOM.name
            }
        }
    }

    private fun putString(context: Context, key: String, value: String) {
        val appContext = context.applicationContext
        prefsWriteScope.launch {
            appContext.settingsStore.edit { it[stringPreferencesKey(key)] = value }
        }
    }

    private const val KEY_MODE = "themeMode"
    private const val KEY_ACCENT = "accentColor"
    private const val KEY_CUSTOM = "customAccentArgb"
    private const val KEY_SECONDARY = "secondaryAccentColor"
    private const val KEY_CUSTOM_SECONDARY = "customSecondaryArgb"
}
