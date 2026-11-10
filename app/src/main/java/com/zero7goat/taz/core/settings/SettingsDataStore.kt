package com.zero7goat.taz.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// One DataStore instance per process for the settings file (filesDir/datastore/tung_settings.preferences_pb).
// DO NOT RENAME: existing user data depends on this exact value (see DataIdentityTest).
private val Context.settingsPreferences: DataStore<Preferences> by preferencesDataStore(name = "tung_settings")

/**
 * Raw persistence for [AppSettings] using DataStore Preferences.
 *
 * Enums are stored by name. A missing or unrecognized stored value falls back to the
 * default, so an old or damaged file can never crash the app.
 */
class SettingsDataStore(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.settingsPreferences)

    val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            // A read failure falls back to defaults; anything else is a real bug.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { it.toAppSettings() }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) = write { it[KEY_THEME_MODE] = mode.name }

    suspend fun setFontSize(size: FontSize) = write { it[KEY_FONT_SIZE] = size.name }

    suspend fun setFontKey(key: String) = write { it[KEY_FONT_KEY] = key }

    suspend fun setAutoLockTimeout(timeout: AutoLockTimeout) =
        write { it[KEY_AUTO_LOCK_TIMEOUT] = timeout.name }

    suspend fun setBiometricEnabled(enabled: Boolean) =
        write { it[KEY_BIOMETRIC_ENABLED] = enabled }

    suspend fun setClipboardClearTimeout(timeout: ClipboardClearTimeout) =
        write { it[KEY_CLIPBOARD_CLEAR_TIMEOUT] = timeout.name }

    suspend fun setHideInRecents(hide: Boolean) = write { it[KEY_HIDE_IN_RECENTS] = hide }

    private suspend fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_FONT_SIZE = stringPreferencesKey("font_size")
        val KEY_FONT_KEY = stringPreferencesKey("font_key")
        val KEY_AUTO_LOCK_TIMEOUT = stringPreferencesKey("auto_lock_timeout")
        val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val KEY_CLIPBOARD_CLEAR_TIMEOUT = stringPreferencesKey("clipboard_clear_timeout")
        val KEY_HIDE_IN_RECENTS = booleanPreferencesKey("hide_in_recents")
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = this[KEY_THEME_MODE].toEnumOrDefault(defaults.themeMode),
            fontSize = this[KEY_FONT_SIZE].toEnumOrDefault(defaults.fontSize),
            fontKey = this[KEY_FONT_KEY]?.takeIf { it.isNotBlank() } ?: defaults.fontKey,
            autoLockTimeout = this[KEY_AUTO_LOCK_TIMEOUT].toEnumOrDefault(defaults.autoLockTimeout),
            biometricEnabled = this[KEY_BIOMETRIC_ENABLED] ?: defaults.biometricEnabled,
            clipboardClearTimeout =
                this[KEY_CLIPBOARD_CLEAR_TIMEOUT].toEnumOrDefault(defaults.clipboardClearTimeout),
            hideInRecents = this[KEY_HIDE_IN_RECENTS] ?: defaults.hideInRecents
        )
    }
}

private inline fun <reified E : Enum<E>> String?.toEnumOrDefault(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default
