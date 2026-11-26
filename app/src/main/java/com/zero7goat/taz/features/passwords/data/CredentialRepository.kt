package com.zero7goat.taz.features.passwords.data

import com.zero7goat.taz.core.database.TrashRow
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.CustomFields
import com.zero7goat.taz.core.security.FieldCipher
import com.zero7goat.taz.core.security.FieldCipherException
import com.zero7goat.taz.features.passwords.domain.CredentialDetail
import com.zero7goat.taz.features.passwords.domain.CredentialInput
import com.zero7goat.taz.features.passwords.domain.PasswordHistory
import com.zero7goat.taz.features.passwords.domain.PasswordHistoryEntry
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

        // A changed password moves into the history (kept encrypted, newest first).
        val previous = dao.getById(input.id)
        val oldPassword = previous?.let { decryptOrNull(it.passwordCipher, aad(it.id, FIELD_PASSWORD)) }
        val oldHistory = previous?.let { decryptHistoryOrEmpty(it) }.orEmpty()
        val history = PasswordHistory.afterSave(oldHistory, oldPassword, input.password, timestamp)
        // The extra lock is changed only by setLocked; an edit never switches it off by accident.
        val locked = previous?.isLocked ?: input.isLocked

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
            fieldsCipher = encryptFields(input.id, fields),
            historyCipher = encryptHistory(input.id, history),
            isLocked = locked
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
            fields = fields,
            history = history,
            isLocked = locked
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
        // Logins with their own lock are found by name only: nothing inside them is searched.
        dao.getAllActive().filterNot { it.isLocked }.mapNotNull { entity ->
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
            fieldsCipher = encryptFields(detail.id, CustomFields.normalize(detail.fields)),
            historyCipher = encryptHistory(detail.id, detail.history),
            isLocked = detail.isLocked
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
        fields = decryptFields(entity),
        history = decryptHistoryOrEmpty(entity),
        isLocked = entity.isLocked
    )

    private fun decryptFields(entity: CredentialEntity): List<CustomField> {
        val blob = entity.fieldsCipher ?: return emptyList()
        return CustomFields.decode(cipher.decrypt(blob, aad(entity.id, FIELD_CUSTOM)))
    }

    private fun decryptOrNull(blob: ByteArray, aad: String): String? =
        try {
            cipher.decrypt(blob, aad)
        } catch (e: FieldCipherException) {
            null
        }

    /** The history, or nothing if there is none or it can't be read (it is never worth failing a save over). */
    private fun decryptHistoryOrEmpty(entity: CredentialEntity): List<PasswordHistoryEntry> {
        val blob = entity.historyCipher ?: return emptyList()
        return try {
            PasswordHistory.decode(cipher.decrypt(blob, aad(entity.id, FIELD_HISTORY)))
        } catch (e: FieldCipherException) {
            emptyList()
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun encryptHistory(id: String, history: List<PasswordHistoryEntry>): ByteArray? =
        if (history.isEmpty()) null else cipher.encrypt(PasswordHistory.encode(history), aad(id, FIELD_HISTORY))

    /**
     * Merges duplicate logins into the most recently edited one: custom fields and notes the others
     * add are kept, their different passwords go into its password history, and the others move to
     * the trash (so a merge can be undone from there). Returns the id that was kept, or null.
     */
    suspend fun merge(ids: List<String>): String? {
        val details = ids.distinct().mapNotNull { (getDetail(it) as? DetailResult.Found)?.detail }
        if (details.size < 2) return null
        val keep = details.maxBy { it.updatedAt }
        val others = details.filter { it.id != keep.id }
        val extraFields = others.flatMap { it.fields }
            .filter { f -> keep.fields.none { it.label == f.label && it.value == f.value } }
            .distinctBy { it.label + "\u0000" + it.value }
            .map { it.copy() }
        val notes = (listOf(keep.notes) + others.map { it.notes }).filter { it.isNotBlank() }.distinct().joinToString("\n\n")
        save(
            CredentialInput(
                id = keep.id, folderId = keep.folderId, serviceName = keep.serviceName, username = keep.username,
                password = keep.password, websiteUrl = keep.websiteUrl.ifBlank { others.firstOrNull { it.websiteUrl.isNotBlank() }?.websiteUrl.orEmpty() },
                notes = notes, isFavorite = keep.isFavorite || others.any { it.isFavorite }, createdAt = minOf(keep.createdAt, others.minOf { it.createdAt }),
                category = keep.category, fields = keep.fields + extraFields
            )
        )
        val oldPasswords = others.map { it.password }.filter { it.isNotEmpty() && it != keep.password }.distinct()
        if (oldPasswords.isNotEmpty()) addToHistory(keep.id, oldPasswords)
        others.forEach { delete(it.id) }
        return keep.id
    }

    private suspend fun addToHistory(id: String, passwords: List<String>) = withContext(Dispatchers.Default) {
        val entity = dao.getById(id) ?: return@withContext
        val current = decryptOrNull(entity.passwordCipher, aad(id, FIELD_PASSWORD))
        val time = now()
        val history = (passwords.map { PasswordHistoryEntry(it, time) } + decryptHistoryOrEmpty(entity))
            .filterNot { it.password == current }
            .distinctBy { it.password }
            .take(PasswordHistory.MAX_ENTRIES)
        dao.upsert(entity.copy(historyCipher = encryptHistory(id, history)))
    }

    /** Turns the extra passcode check for this login on or off. */
    suspend fun setLocked(id: String, locked: Boolean) = dao.setLocked(id, locked)

    /** Forgets the earlier passwords of a login. */
    suspend fun clearHistory(id: String) = dao.clearHistory(id)

    private fun encryptFields(id: String, fields: List<CustomField>): ByteArray? =
        if (fields.isEmpty()) null else cipher.encrypt(CustomFields.encode(fields), aad(id, FIELD_CUSTOM))

    companion object {
        private const val FIELD_USERNAME = "username"
        private const val FIELD_PASSWORD = "password"
        private const val FIELD_NOTES = "notes"
        private const val FIELD_CUSTOM = "fields"
        private const val FIELD_HISTORY = "history"

        /** Binds each ciphertext to its row and field. */
        // DO NOT RENAME: existing user data depends on this exact value (see DataIdentityTest).
        internal fun aad(id: String, field: String) = "taz.credential.v1|$id|$field"
    }
}
