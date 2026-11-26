package com.zero7goat.taz.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// DO NOT RENAME: the 2.0 options live in filesDir/datastore/taz_features.preferences_pb.
private val Context.featurePreferences: DataStore<Preferences> by preferencesDataStore(name = "taz_features")

enum class AutoBackupFrequency(val days: Int) { OFF(0), DAILY(1), WEEKLY(7) }

/** Launcher icon and name shown on the home screen. [key] is stored and must never change. */
enum class AppDisguise(val key: String, val label: String) {
    NONE("none", "TAZ"),
    CALCULATOR("calculator", "Calculator"),
    NOTES("notes", "Notes"),
    WEATHER("weather", "Weather");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/**
 * The options added in TAZ 2.0. They are device-specific (a backup folder, which icon is shown),
 * so unlike [AppSettings] they are not carried in backups. Nothing here is secret in plain text:
 * the automatic-backup passphrase is stored only as Keystore ciphertext (see AutoBackupSecret).
 */
data class FeatureSettings(
    val autoBackupFrequency: AutoBackupFrequency = AutoBackupFrequency.OFF,
    /** A folder the user picked (Storage Access Framework tree URI), or empty. */
    val autoBackupFolder: String = "",
    val autoBackupKeep: Int = 5,
    val autoBackupLastAt: Long = 0L,
    /** A short, non-sensitive reason the last automatic backup failed, or empty. */
    val autoBackupLastError: String = "",
    /** Erase everything after this many wrong passcodes in a row; 0 = never. */
    val wipeAfterAttempts: Int = 0,
    val lockOnScreenOff: Boolean = false,
    val disguise: AppDisguise = AppDisguise.NONE,
    val dynamicColor: Boolean = false,
    /** Remind to change passwords older than this many months; 0 = off. */
    val passwordAgeMonths: Int = 0,
    /** Remind this many days before a date field labelled like an expiry date; 0 = off. */
    val expiryReminderDays: Int = 30,
    val onboardingDone: Boolean = false,
    /** The versionCode whose "What's new" screen was last shown. */
    val lastSeenVersion: Int = 0,
    /** Reminders already shown, so each appears once. */
    val notifiedKeys: Set<String> = emptySet(),
    /** True once every stored file has been encrypted. */
    val filesEncrypted: Boolean = false
)

class FeaturePrefs(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.featurePreferences)

    val settings: Flow<FeatureSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toFeatureSettings() }
        .distinctUntilChanged()

    suspend fun current(): FeatureSettings = settings.first()

    /** The stored automatic-backup ciphertext, or null. Only AutoBackupSecret reads this. */
    suspend fun rawSecret(): String? = dataStore.data.first()[AUTO_BACKUP_SECRET]

    suspend fun update(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    suspend fun setAutoBackup(frequency: AutoBackupFrequency, folder: String, keep: Int) = update {
        it[FREQUENCY] = frequency.name
        it[FOLDER] = folder
        it[KEEP] = keep.coerceIn(1, 30)
    }

    suspend fun recordAutoBackup(at: Long, error: String) = update {
        if (error.isEmpty()) it[LAST_AT] = at
        it[LAST_ERROR] = error
    }

    suspend fun setWipeAfterAttempts(count: Int) = update { it[WIPE_AFTER] = count }
    suspend fun setLockOnScreenOff(on: Boolean) = update { it[LOCK_SCREEN_OFF] = on }
    suspend fun setDisguise(disguise: AppDisguise) = update { it[DISGUISE] = disguise.key }
    suspend fun setDynamicColor(on: Boolean) = update { it[DYNAMIC_COLOR] = on }
    suspend fun setPasswordAgeMonths(months: Int) = update { it[PASSWORD_AGE] = months }
    suspend fun setExpiryReminderDays(days: Int) = update { it[EXPIRY_DAYS] = days }
    suspend fun setOnboardingDone() = update { it[ONBOARDING] = true }
    suspend fun setLastSeenVersion(version: Int) = update { it[LAST_SEEN] = version }
    suspend fun setFilesEncrypted() = update { it[FILES_ENCRYPTED] = true }

    suspend fun addNotified(keys: Collection<String>) = update {
        // Bounded, so years of reminders can't grow the file without limit.
        it[NOTIFIED] = ((it[NOTIFIED] ?: emptySet()) + keys).toList().takeLast(500).toSet()
    }

    private fun Preferences.toFeatureSettings() = FeatureSettings(
        autoBackupFrequency = AutoBackupFrequency.entries.firstOrNull { it.name == this[FREQUENCY] } ?: AutoBackupFrequency.OFF,
        autoBackupFolder = this[FOLDER].orEmpty(),
        autoBackupKeep = this[KEEP] ?: 5,
        autoBackupLastAt = this[LAST_AT] ?: 0L,
        autoBackupLastError = this[LAST_ERROR].orEmpty(),
        wipeAfterAttempts = this[WIPE_AFTER] ?: 0,
        lockOnScreenOff = this[LOCK_SCREEN_OFF] ?: false,
        disguise = AppDisguise.fromKey(this[DISGUISE]),
        dynamicColor = this[DYNAMIC_COLOR] ?: false,
        passwordAgeMonths = this[PASSWORD_AGE] ?: 0,
        expiryReminderDays = this[EXPIRY_DAYS] ?: 30,
        onboardingDone = this[ONBOARDING] ?: false,
        lastSeenVersion = this[LAST_SEEN] ?: 0,
        notifiedKeys = this[NOTIFIED] ?: emptySet(),
        filesEncrypted = this[FILES_ENCRYPTED] ?: false
    )

    companion object {
        private val FREQUENCY = stringPreferencesKey("auto_backup_frequency")
        private val FOLDER = stringPreferencesKey("auto_backup_folder")
        private val KEEP = intPreferencesKey("auto_backup_keep")
        private val LAST_AT = longPreferencesKey("auto_backup_last_at")
        private val LAST_ERROR = stringPreferencesKey("auto_backup_last_error")
        private val WIPE_AFTER = intPreferencesKey("wipe_after_attempts")
        private val LOCK_SCREEN_OFF = booleanPreferencesKey("lock_on_screen_off")
        private val DISGUISE = stringPreferencesKey("disguise")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val PASSWORD_AGE = intPreferencesKey("password_age_months")
        private val EXPIRY_DAYS = intPreferencesKey("expiry_reminder_days")
        private val ONBOARDING = booleanPreferencesKey("onboarding_done")
        private val LAST_SEEN = intPreferencesKey("last_seen_version")
        private val NOTIFIED = stringSetPreferencesKey("notified_keys")
        private val FILES_ENCRYPTED = booleanPreferencesKey("files_encrypted")

        /** The encrypted automatic-backup passphrase (Base64 of Keystore ciphertext). */
        val AUTO_BACKUP_SECRET = stringPreferencesKey("auto_backup_secret")
    }
}
