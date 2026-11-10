package com.zero7goat.taz.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Separate from the settings file: this one holds passcode verification data.
// DO NOT RENAME: existing user data depends on this exact value (see DataIdentityTest).
private val Context.securityPreferences: DataStore<Preferences> by preferencesDataStore(name = "tung_security")

/**
 * Stores what is needed to verify the passcode, never the passcode itself:
 * a random salt, the PBKDF2 iteration count, and the PBKDF2 hash. The hash is additionally
 * encrypted with the device's Keystore key, so a copied data file cannot be used to guess
 * the passcode offline. Failed-attempt counters live here too.
 */
class PasscodeStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: FieldCipher
) {

    constructor(context: Context) : this(context.applicationContext.securityPreferences, KeystoreFieldCipher())

    sealed interface Loaded {
        data object None : Loaded

        class Present(val salt: ByteArray, val iterations: Int, val hash: ByteArray) : Loaded {
            override fun toString(): String = "Present(hidden)"
        }

        /** A passcode is recorded but cannot be read (for example, the Keystore key is gone). */
        data object Unreadable : Loaded
    }

    class Failures(val count: Int, val lockedUntilMillis: Long)

    private val data: Flow<Preferences> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }

    /** True while a passcode is set. */
    val hasPasscode: Flow<Boolean> = data.map { it[KEY_HASH] != null }.distinctUntilChanged()

    /** Reads and decrypts the verification data. May call Keystore, so use a background thread. */
    suspend fun load(): Loaded {
        val prefs = data.first()
        val hashText = prefs[KEY_HASH] ?: return Loaded.None
        val saltText = prefs[KEY_SALT] ?: return Loaded.Unreadable
        val iterations = prefs[KEY_ITERATIONS] ?: return Loaded.Unreadable
        return try {
            val decoder = Base64.getDecoder()
            val hashBase64 = cipher.decrypt(decoder.decode(hashText), AAD)
            Loaded.Present(decoder.decode(saltText), iterations, decoder.decode(hashBase64))
        } catch (e: Exception) {
            Loaded.Unreadable
        }
    }

    /** Saves a new passcode and clears any failed-attempt counters. */
    suspend fun save(salt: ByteArray, iterations: Int, hash: ByteArray) {
        val encoder = Base64.getEncoder()
        val wrapped = cipher.encrypt(encoder.encodeToString(hash), AAD)
        dataStore.edit { prefs ->
            prefs[KEY_SALT] = encoder.encodeToString(salt)
            prefs[KEY_ITERATIONS] = iterations
            prefs[KEY_HASH] = encoder.encodeToString(wrapped)
            prefs.remove(KEY_FAILED)
            prefs.remove(KEY_LOCKED_UNTIL)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    suspend fun readFailures(): Failures {
        val prefs = data.first()
        return Failures(prefs[KEY_FAILED] ?: 0, prefs[KEY_LOCKED_UNTIL] ?: 0L)
    }

    suspend fun writeFailures(count: Int, lockedUntilMillis: Long) {
        dataStore.edit { prefs ->
            if (count <= 0) prefs.remove(KEY_FAILED) else prefs[KEY_FAILED] = count
            if (lockedUntilMillis <= 0L) prefs.remove(KEY_LOCKED_UNTIL) else prefs[KEY_LOCKED_UNTIL] = lockedUntilMillis
        }
    }

    private companion object {
        // DO NOT RENAME: existing user data depends on this exact value (see DataIdentityTest).
        const val AAD = "tung.passcode.v1"
        val KEY_SALT = stringPreferencesKey("passcode_salt")
        val KEY_ITERATIONS = intPreferencesKey("passcode_iterations")
        val KEY_HASH = stringPreferencesKey("passcode_hash")
        val KEY_FAILED = intPreferencesKey("failed_attempts")
        val KEY_LOCKED_UNTIL = longPreferencesKey("locked_until")
    }
}
