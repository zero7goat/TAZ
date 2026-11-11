package com.zero7goat.taz.features.passwords.data

import com.zero7goat.taz.core.database.TrashRow
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.CustomFields
import com.zero7goat.taz.core.security.FieldCipher
import com.zero7goat.taz.core.security.FieldCipherException
import com.zero7goat.taz.features.passwords.domain.CredentialDetail
import com.zero7goat.taz.features.passwords.domain.CredentialInput
import com.zero7goat.taz.features.passwords.domain.canSaveCredential
import com.zero7goat.taz.features.passwords.domain.extractDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONException

/** Result of loading one login. */
sealed interface DetailResult {
    data class Found(val detail: CredentialDetail) : DetailResult
    data object Missing : DetailResult

    /** The entry exists but cannot be decrypted (for example, the key is gone). */
    data object Unreadable : DetailResult
}

/**
 * What search may look at for one login: never the password, and never the value of a hidden
 * custom field (those are blanked before this object exists). Lives only in memory.
 */
class SearchableLogin(
    val id: String,
    val username: String,
    val notes: String,
    val fields: List<CustomField>
) {
    override fun toString(): String = "SearchableLogin(id=$id)"
}

/**
 * The only place that encrypts or decrypts credentials. Crypto runs off the main thread.
 * Nothing here logs, and errors never include credential values.
 */
class CredentialRepository(
    private val dao: CredentialDao,
    private val cipher: FieldCipher,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private class CachedUsername(val cipherBytes: ByteArray, val value: String)

    // Avoids decrypting every visible username again on each list refresh. Lives only as long
    // as this repository (the ViewModel) and is cleared by clearCache().
    private val usernameCache = HashMap<String, CachedUsername>()

    fun observeListRows(): Flow<List<CredentialListRow>> = dao.observeListRows()

    /** The decrypted username of a list row, or an empty string if it cannot be read. */
    suspend fun usernameFor(row: CredentialListRow): String = withContext(Dispatchers.Default) {
        val cached = synchronized(usernameCache) {
            usernameCache[row.id]?.takeIf { it.cipherBytes.contentEquals(row.usernameCipher) }
        }
        if (cached != null) return@withContext cached.value

        val value = try {
            cipher.decrypt(row.usernameCipher, aad(row.id, FIELD_USERNAME))
        } catch (e: FieldCipherException) {
            ""
        }
        synchronized(usernameCache) { usernameCache[row.id] = CachedUsername(row.usernameCipher, value) }
        value
    }

    suspend fun getDetail(id: String): DetailResult = withContext(Dispatchers.Default) {
        val entity = dao.getById(id) ?: return@withContext DetailResult.Missing
        try {
            DetailResult.Found(decrypt(entity))
        } catch (e: FieldCipherException) {
            DetailResult.Unreadable
        } catch (e: JSONException) {
            DetailResult.Unreadable
        }
    }

    /**
     * Encrypts and saves [input]. Throws [IllegalArgumentException] if the name is empty, or the
     * password of a Login is empty, or [FieldCipherException] if encryption fails. Returns what was
     * stored.
     */
    suspend fun save(input: CredentialInput): CredentialDetail = withContext(Dispatchers.Default) {
        val serviceName = input.serviceName.trim()
        require(canSaveCredential(serviceName, input.password, input.category)) { "Name or password missing" }

        val username = input.username.trim()
        val websiteUrl = input.websiteUrl.trim()
        val notes = input.notes.trim()
        val fields = CustomFields.normalize(input.fields)
        val timestamp = now()
        val createdAt = if (input.createdAt == 0L) timestamp else input.createdAt

        val entity = CredentialEntity(
            id = input.id,
            folderId = input.folderId,
            serviceName = serviceName,
            websiteUrl = websiteUrl,
            domain = extractDomain(websiteUrl),
            usernameCipher = cipher.encrypt(username, aad(input.id, FIELD_USERNAME)),
            passwordCipher = cipher.encrypt(input.password, aad(input.id, FIELD_PASSWORD)),
            noteCipher = cipher.encrypt(notes, aad(input.id, FIELD_NOTES)),
            isFavorite = input.isFavorite,
            createdAt = createdAt,
            updatedAt = timestamp,
            category = input.category,
            fieldsCipher = encryptFields(input.id, fields)
        )
        dao.upsert(entity)
        synchronized(usernameCache) { usernameCache.remove(input.id) }

        CredentialDetail(
            id = input.id,
            folderId = input.folderId,
            serviceName = serviceName,
            username = username,
            password = input.password,
            websiteUrl = websiteUrl,
            notes = notes,
            isFavorite = input.isFavorite,
            createdAt = createdAt,
            updatedAt = timestamp,
            category = input.category,
            fields = fields
        )
    }

    /** Moves the login to the trash. Its encrypted data is untouched until it is purged. */
    suspend fun delete(id: String) {
        dao.moveToTrash(id, now())
        synchronized(usernameCache) { usernameCache.remove(id) }
    }

    suspend fun restore(id: String) = dao.restoreFromTrash(id)

    fun observeTrash(): Flow<List<TrashRow>> = dao.observeTrash()

    suspend fun trashedIds(): List<String> = dao.trashedIds()

    suspend fun trashedBefore(cutoff: Long): List<String> = dao.trashedBefore(cutoff)

    /** Permanently deletes the login. Its custom fields are in the same row and go with it. */
    suspend fun purge(id: String) {
        dao.deleteById(id)
        synchronized(usernameCache) { usernameCache.remove(id) }
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) = dao.setFavorite(id, isFavorite)

    /** Moves an entry into [folderId], or to the top level when null. */
    suspend fun setFolder(id: String, folderId: String?) = dao.setFolder(id, folderId)

    /**
     * Decrypts what search may look at, for logins outside the trash: username, notes and custom
     * fields. Passwords are never read, and hidden field values are blanked (their labels stay
     * searchable). Logins that cannot be decrypted are skipped.
     */
    suspend fun decryptForSearch(): List<SearchableLogin> = withContext(Dispatchers.Default) {
        dao.getAllActive().mapNotNull { entity ->
            try {
                SearchableLogin(
                    id = entity.id,
                    username = cipher.decrypt(entity.usernameCipher, aad(entity.id, FIELD_USERNAME)),
                    notes = cipher.decrypt(entity.noteCipher, aad(entity.id, FIELD_NOTES)),
                    fields = decryptFields(entity).map { field ->
                        if (CustomFields.isSearchable(field)) field else field.copy(value = "")
                    }
                )
            } catch (e: FieldCipherException) {
                null
            } catch (e: JSONException) {
                null
            }
        }
    }

    /**
     * Decrypts every login for an encrypted backup. Logins that cannot be decrypted are left out
     * (never replaced with empty values) and counted in the second value. Nothing is written.
     */
    suspend fun decryptAllForBackup(): Pair<List<CredentialDetail>, Int> = withContext(Dispatchers.Default) {
        var unreadable = 0
        val details = dao.getAllActive().mapNotNull { entity ->
            try {
                decrypt(entity)
            } catch (e: FieldCipherException) {
                unreadable++
                null
            } catch (e: JSONException) {
                unreadable++
                null
            }
        }
        details to unreadable
    }

    /**
     * Encrypts a login from a backup with this installation's key, in exactly the format and with
     * the same associated data as [save], keeping its timestamps. Does not write anything.
     * Throws [FieldCipherException] if encryption fails.
     */
    fun encryptForRestore(detail: CredentialDetail): CredentialEntity =
        CredentialEntity(
            id = detail.id,
            folderId = detail.folderId,
            serviceName = detail.serviceName,
            websiteUrl = detail.websiteUrl,
            domain = extractDomain(detail.websiteUrl),
            usernameCipher = cipher.encrypt(detail.username, aad(detail.id, FIELD_USERNAME)),
            passwordCipher = cipher.encrypt(detail.password, aad(detail.id, FIELD_PASSWORD)),
            noteCipher = cipher.encrypt(detail.notes, aad(detail.id, FIELD_NOTES)),
            isFavorite = detail.isFavorite,
            createdAt = detail.createdAt,
            updatedAt = detail.updatedAt,
            category = detail.category,
            fieldsCipher = encryptFields(detail.id, CustomFields.normalize(detail.fields))
        )

    fun clearCache() {
        synchronized(usernameCache) { usernameCache.clear() }
    }

    private fun decrypt(entity: CredentialEntity) = CredentialDetail(
        id = entity.id,
        folderId = entity.folderId,
        serviceName = entity.serviceName,
        username = cipher.decrypt(entity.usernameCipher, aad(entity.id, FIELD_USERNAME)),
        password = cipher.decrypt(entity.passwordCipher, aad(entity.id, FIELD_PASSWORD)),
        websiteUrl = entity.websiteUrl,
        notes = cipher.decrypt(entity.noteCipher, aad(entity.id, FIELD_NOTES)),
        isFavorite = entity.isFavorite,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        category = entity.category,
        fields = decryptFields(entity)
    )

    private fun decryptFields(entity: CredentialEntity): List<CustomField> {
        val blob = entity.fieldsCipher ?: return emptyList()
        return CustomFields.decode(cipher.decrypt(blob, aad(entity.id, FIELD_CUSTOM)))
    }

    private fun encryptFields(id: String, fields: List<CustomField>): ByteArray? =
        if (fields.isEmpty()) null else cipher.encrypt(CustomFields.encode(fields), aad(id, FIELD_CUSTOM))

    companion object {
        private const val FIELD_USERNAME = "username"
        private const val FIELD_PASSWORD = "password"
        private const val FIELD_NOTES = "notes"
        private const val FIELD_CUSTOM = "fields"

        /** Binds each ciphertext to its row and field. */
        // DO NOT RENAME: existing user data depends on this exact value (see DataIdentityTest).
        internal fun aad(id: String, field: String) = "taz.credential.v1|$id|$field"
    }
}
