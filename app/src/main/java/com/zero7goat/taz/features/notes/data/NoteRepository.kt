package com.zero7goat.taz.features.notes.data

import com.zero7goat.taz.core.database.TrashRow
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.CustomFields
import com.zero7goat.taz.core.security.FieldCipher
import com.zero7goat.taz.core.security.FieldCipherException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** The custom fields of a note, or that they exist but cannot be decrypted on this device. */
sealed interface NoteFieldsLoad {
    data class Ok(val fields: List<CustomField>) : NoteFieldsLoad
    data object Unreadable : NoteFieldsLoad
}

class NoteRepository(
    private val noteDao: NoteDao,
    private val images: NoteImageStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Encrypts note custom fields. Without it, fields are neither read nor written. */
    private val cipher: FieldCipher? = null
) {

    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    suspend fun getNote(id: String): NoteEntity? = noteDao.getById(id)

    /**
     * Saves [note] and sets its updatedAt. A createdAt of 0 means "new note" and is
     * replaced with the current time. Returns the note as stored.
     *
     * [fields] null leaves the stored custom fields exactly as they are (used when they could not
     * be read, so they are never overwritten). Otherwise they are encrypted and saved in the same
     * transaction as the note. Throws [FieldCipherException] if encryption fails; nothing is
     * written then.
     */
    suspend fun save(note: NoteEntity, fields: List<CustomField>? = null): NoteEntity {
        val timestamp = now()
        val toSave = note.copy(
            createdAt = if (note.createdAt == 0L) timestamp else note.createdAt,
            updatedAt = timestamp
        )
        val fieldCipher = cipher
        if (fields == null || fieldCipher == null) {
            noteDao.upsert(toSave)
        } else {
            val clean = CustomFields.normalize(fields)
            val blob = if (clean.isEmpty()) {
                null
            } else {
                withContext(Dispatchers.Default) { fieldCipher.encrypt(CustomFields.encode(clean), fieldsAad(note.id)) }
            }
            noteDao.saveWithFields(toSave, blob?.let { NoteFieldsEntity(note.id, it) })
        }
        return toSave
    }

    suspend fun loadFields(id: String): NoteFieldsLoad = withContext(Dispatchers.Default) {
        val stored = noteDao.getFields(id) ?: return@withContext NoteFieldsLoad.Ok(emptyList())
        decodeFields(id, stored.fieldsCipher)
    }

    /** Decrypts every note's fields (notes outside the trash), for search and backup. */
    suspend fun loadAllFields(): Pair<Map<String, List<CustomField>>, Int> = withContext(Dispatchers.Default) {
        var unreadable = 0
        val result = HashMap<String, List<CustomField>>()
        noteDao.getAllActiveFields().forEach { row ->
            when (val load = decodeFields(row.noteId, row.fieldsCipher)) {
                is NoteFieldsLoad.Ok -> if (load.fields.isNotEmpty()) result[row.noteId] = load.fields
                NoteFieldsLoad.Unreadable -> unreadable++
            }
        }
        result to unreadable
    }

    /** Encrypts restored fields with this installation's key. Throws [FieldCipherException]. */
    fun encryptFieldsForRestore(noteId: String, fields: List<CustomField>): NoteFieldsEntity? {
        val fieldCipher = cipher ?: throw FieldCipherException("Encryption key unavailable")
        val clean = CustomFields.normalize(fields)
        if (clean.isEmpty()) return null
        return NoteFieldsEntity(noteId, fieldCipher.encrypt(CustomFields.encode(clean), fieldsAad(noteId)))
    }

    private fun decodeFields(id: String, blob: ByteArray): NoteFieldsLoad {
        val fieldCipher = cipher ?: return NoteFieldsLoad.Unreadable
        return try {
            NoteFieldsLoad.Ok(CustomFields.decode(fieldCipher.decrypt(blob, fieldsAad(id))))
        } catch (e: FieldCipherException) {
            NoteFieldsLoad.Unreadable
        } catch (e: org.json.JSONException) {
            NoteFieldsLoad.Unreadable
        }
    }

    /** Moves the note to the trash. Nothing is removed, so it can be restored until it is purged. */
    suspend fun deleteNote(id: String) = noteDao.moveToTrash(id, now())

    suspend fun restoreNote(id: String) = noteDao.restoreFromTrash(id)

    fun observeTrash(): Flow<List<TrashRow>> = noteDao.observeTrash()

    suspend fun trashedIds(): List<String> = noteDao.trashedIds()

    suspend fun trashedBefore(cutoff: Long): List<String> = noteDao.trashedBefore(cutoff)

    /**
     * Permanently deletes the note and its custom fields, then the images it referenced that no
     * other note (including notes in the trash) still references. An image shared with another
     * note is kept.
     */
    suspend fun purgeNote(id: String) {
        val body = noteDao.getById(id)?.body
        noteDao.deleteFields(id)
        noteDao.deleteById(id)
        val store = images ?: return
        val released = body?.let { NoteImageStore.referencedImageNames(it) }.orEmpty()
        if (released.isEmpty()) return
        val stillUsed = noteDao.getAllBodies().flatMapTo(HashSet()) { NoteImageStore.referencedImageNames(it) }
        withContext(Dispatchers.IO) { store.delete(released - stillUsed) }
    }

    /**
     * Removes images that no note references any more (inserted and then removed again, or
     * inserted into a note that was discarded). Only images more than a day old are touched, and
     * nothing is removed when there are no notes at all, so a database problem cannot wipe images.
     */
    suspend fun cleanupUnusedImages() {
        val store = images ?: return
        val bodies = noteDao.getAllBodies()
        if (bodies.isEmpty()) return
        withContext(Dispatchers.IO) { store.deleteUnreferenced(bodies, olderThanMs = ORPHAN_MIN_AGE_MS) }
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) = noteDao.setFavorite(id, isFavorite)

    suspend fun setPinned(id: String, isPinned: Boolean) = noteDao.setPinned(id, isPinned)

    /** Moves a note into [folderId], or to the top level when null. */
    suspend fun setFolder(id: String, folderId: String?) = noteDao.setFolder(id, folderId)

    companion object {
        private const val ORPHAN_MIN_AGE_MS = 24L * 60 * 60 * 1000

        /** Binds a note's field blob to that note. */
        // DO NOT RENAME: existing user data depends on this exact value (see DataIdentityTest).
        fun fieldsAad(noteId: String) = "taz.note.v1|$noteId|fields"
    }
}
