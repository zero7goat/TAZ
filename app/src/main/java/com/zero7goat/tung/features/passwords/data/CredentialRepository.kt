package com.zero7goat.tung.features.passwords.data

import com.zero7goat.tung.core.security.FieldCipher
import com.zero7goat.tung.core.security.FieldCipherException
import com.zero7goat.tung.features.passwords.domain.CredentialDetail
import com.zero7goat.tung.features.passwords.domain.CredentialInput
import com.zero7goat.tung.features.passwords.domain.extractDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Result of loading one login. */
sealed interface DetailResult {
    data class Found(val detail: CredentialDetail) : DetailResult
    data object Missing : DetailResult

    /** The entry exists but cannot be decrypted (for example, the key is gone). */
    data object Unreadable : DetailResult
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
            DetailResult.Found(
                CredentialDetail(
                    id = entity.id,
                    folderId = entity.folderId,
                    serviceName = entity.serviceName,
                    username = cipher.decrypt(entity.usernameCipher, aad(id, FIELD_USERNAME)),
                    password = cipher.decrypt(entity.passwordCipher, aad(id, FIELD_PASSWORD)),
                    websiteUrl = entity.websiteUrl,
                    notes = cipher.decrypt(entity.noteCipher, aad(id, FIELD_NOTES)),
                    isFavorite = entity.isFavorite,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
                )
            )
        } catch (e: FieldCipherException) {
            DetailResult.Unreadable
        }
    }

    /**
     * Encrypts and saves [input]. Throws [IllegalArgumentException] if the service name or
     * password is empty, or [FieldCipherException] if encryption fails. Returns what was stored.
     */
    suspend fun save(input: CredentialInput): CredentialDetail = withContext(Dispatchers.Default) {
        val serviceName = input.serviceName.trim()
        require(serviceName.isNotEmpty()) { "Service name is required" }
        require(input.password.isNotEmpty()) { "Password is required" }

        val username = input.username.trim()
        val websiteUrl = input.websiteUrl.trim()
        val notes = input.notes.trim()
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
            updatedAt = timestamp
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
            updatedAt = timestamp
        )
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
        synchronized(usernameCache) { usernameCache.remove(id) }
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) = dao.setFavorite(id, isFavorite)

    /** Moves an entry into [folderId], or to the top level when null. */
    suspend fun setFolder(id: String, folderId: String?) = dao.setFolder(id, folderId)

    /**
     * Decrypts every login for an encrypted backup. Logins that cannot be decrypted are left out
     * (never replaced with empty values) and counted in the second value. Nothing is written.
     */
    suspend fun decryptAllForBackup(): Pair<List<CredentialDetail>, Int> = withContext(Dispatchers.Default) {
        var unreadable = 0
        val details = dao.getAll().mapNotNull { entity ->
            try {
                CredentialDetail(
                    id = entity.id,
                    folderId = entity.folderId,
                    serviceName = entity.serviceName,
                    username = cipher.decrypt(entity.usernameCipher, aad(entity.id, FIELD_USERNAME)),
                    password = cipher.decrypt(entity.passwordCipher, aad(entity.id, FIELD_PASSWORD)),
                    websiteUrl = entity.websiteUrl,
                    notes = cipher.decrypt(entity.noteCipher, aad(entity.id, FIELD_NOTES)),
                    isFavorite = entity.isFavorite,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
                )
            } catch (e: FieldCipherException) {
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
            updatedAt = detail.updatedAt
        )

    fun clearCache() {
        synchronized(usernameCache) { usernameCache.clear() }
    }

    private companion object {
        const val FIELD_USERNAME = "username"
        const val FIELD_PASSWORD = "password"
        const val FIELD_NOTES = "notes"

        /** Binds each ciphertext to its row and field. */
        fun aad(id: String, field: String) = "tung.credential.v1|$id|$field"
    }
}
