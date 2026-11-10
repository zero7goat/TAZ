package com.zero7goat.taz.features.notes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.zero7goat.taz.core.database.TrashRow
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    /** Inserts the note, or replaces it if the id already exists. */
    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    /** Pinned notes first, then by last edit. This is only an ordering, not a section. */
    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)

    // Pin, favorite and folder changes deliberately leave updatedAt untouched.
    @Query("UPDATE notes SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE notes SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: String, isPinned: Boolean)

    @Query("UPDATE notes SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?)

    /** Every note body, for finding which note images are still referenced. */
    @Query("SELECT body FROM notes")
    suspend fun getAllBodies(): List<String>

    // Trash. Moving to the trash only sets deletedAt; nothing is removed until the item is purged.

    @Query("UPDATE notes SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun moveToTrash(id: String, deletedAt: Long)

    @Query("UPDATE notes SET deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: String)

    @Query("SELECT id, title AS label, deletedAt FROM notes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<TrashRow>>

    @Query("SELECT id FROM notes WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun trashedBefore(cutoff: Long): List<String>

    @Query("SELECT id FROM notes WHERE deletedAt IS NOT NULL")
    suspend fun trashedIds(): List<String>

    // Used only by backup, restore and export.

    /** Every row including the trash. Used to recognise the database state after a restore. */
    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    /** Rows that are not in the trash: what a backup or export contains. */
    @Query("SELECT * FROM notes WHERE deletedAt IS NULL")
    suspend fun getAllActive(): List<NoteEntity>

    @Insert
    suspend fun insertAll(notes: List<NoteEntity>)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}
