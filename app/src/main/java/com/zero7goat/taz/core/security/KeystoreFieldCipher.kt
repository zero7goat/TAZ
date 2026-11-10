package com.zero7goat.taz.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [FieldCipher] backed by an AES-256 key held in Android Keystore.
 *
 * The key is generated on the device, cannot be exported, and is never written to
 * preferences or files. It is not tied to user authentication yet: app lock and biometrics
 * will gate access to the screens. (Binding the key to biometrics would make every saved
 * password unreadable if the user's fingerprints change.)
 *
 * If the key is ever lost (for example after a factory reset or a damaged Keystore), the
 * encrypted values cannot be recovered. Encrypted backups are the recovery path.
 */
class KeystoreFieldCipher : FieldCipher by AesGcmFieldCipher({ MasterKey.get() })

/**
 * Deletes the master key. Everything encrypted with it becomes permanently unreadable, so this is
 * only used by the explicit "erase all data" recovery.
 */
fun deleteMasterKey() = MasterKey.delete()

/** Alias of the version 1 key. A later key version gets a new alias. */
private object MasterKey {
    private const val PROVIDER = "AndroidKeyStore"
    // DO NOT RENAME: existing user data depends on this exact value (see DataIdentityTest).
    private const val ALIAS = "tung_master_v1"

    @Volatile
    private var cached: SecretKey? = null

    @Synchronized
    fun get(): SecretKey {
        cached?.let { return it }
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        val key = existing ?: createKey()
        cached = key
        return key
    }

    @Synchronized
    fun delete() {
        cached = null
        try {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        } catch (e: Exception) {
            // Nothing more can be done; the next start creates a fresh key if needed.
        }
    }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
