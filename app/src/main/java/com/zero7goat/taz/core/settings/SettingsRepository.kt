package com.zero7goat.taz.core.settings

import kotlinx.coroutines.flow.Flow

/**
 * The app's access point for settings. Exposes one [Flow] of all settings and one setter
 * per preference. Rules that depend on other parts of the app (for example, biometrics
 * requiring a passcode) will be added here when those parts exist.
 */
class SettingsRepository(private val dataStore: SettingsDataStore) {

    val settings: Flow<AppSettings> = dataStore.settings

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.setThemeMode(mode)

    suspend fun setFontSize(size: FontSize) = dataStore.setFontSize(size)

    /** A blank key resets to the system font. Keys are validated against bundled fonts later. */
    suspend fun setFontKey(key: String) =
        dataStore.setFontKey(key.trim().ifEmpty { DEFAULT_FONT_KEY })

    suspend fun setAutoLockTimeout(timeout: AutoLockTimeout) =
        dataStore.setAutoLockTimeout(timeout)

    suspend fun setBiometricEnabled(enabled: Boolean) = dataStore.setBiometricEnabled(enabled)

    suspend fun setClipboardClearTimeout(timeout: ClipboardClearTimeout) =
        dataStore.setClipboardClearTimeout(timeout)

    suspend fun setHideInRecents(hide: Boolean) = dataStore.setHideInRecents(hide)
}
