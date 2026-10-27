package com.zero7goat.tung.features.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class NoteRepository(
    private val noteDao: NoteDao,
    private val images: NoteImageStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    fun observeNotes(): Flow<List<NoteEntity>> = noteDao.observeAll()

    suspend fun getNote(id: String): NoteEntity? = noteDao.getById(id)

    /**
     * Saves [note] and sets its updatedAt. A createdAt of 0 means "new note" and is
     * replaced with the current time. Returns the note as stored.
     */
    suspend fun save(note: NoteEntity): NoteEntity {
        val timestamp = now()
        val toSave = note.copy(
            createdAt = if (note.createdAt == 0L) timestamp else note.createdAt,
            updatedAt = timestamp
        )
        noteDao.upsert(toSave)
        return toSave
    }

    /**
     * Deletes the note, then the images it referenced that no other note still references.
     * An image shared with another note is kept.
     */
    suspend fun deleteNote(id: String) {
        val body = noteDao.getById(id)?.body
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

    private companion object {
        const val ORPHAN_MIN_AGE_MS = 24L * 60 * 60 * 1000
    }
}
