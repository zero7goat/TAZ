package com.zero7goat.taz.features.files.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zero7goat.taz.core.database.TrashRow
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {

    @Insert
    suspend fun insert(file: FileEntity)

    @Query("SELECT * FROM files WHERE deletedAt IS NULL ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<FileEntity?>

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun getById(id: String): FileEntity?

    /** Names already used in a folder (null means the top level). */
    @Query("SELECT id, displayName FROM files WHERE folderId IS :folderId AND deletedAt IS NULL")
    suspend fun namesInFolder(folderId: String?): List<FileNameRow>

    @Query("SELECT storedPath FROM files")
    suspend fun allStoredPaths(): List<String>

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE files SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE files SET folderId = :folderId, displayName = :displayName WHERE id = :id")
    suspend fun move(id: String, folderId: String?, displayName: String)

    @Query(
        "UPDATE files SET displayName = :displayName, keywords = :keywords, " +
            "description = :description, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateMetadata(
        id: String,
        displayName: String,
        keywords: String,
        description: String,
        updatedAt: Long
    )

    // Trash. Moving to the trash only sets deletedAt; the stored copy stays until the file is purged.

    @Query("UPDATE files SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun moveToTrash(id: String, deletedAt: Long)

    @Query("UPDATE files SET deletedAt = NULL, displayName = :displayName WHERE id = :id")
    suspend fun restoreFromTrash(id: String, displayName: String)

    @Query("SELECT id, displayName AS label, deletedAt FROM files WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<TrashRow>>

    @Query("SELECT id FROM files WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun trashedBefore(cutoff: Long): List<String>

    @Query("SELECT id FROM files WHERE deletedAt IS NOT NULL")
    suspend fun trashedIds(): List<String>

    // Used only by backup, restore and export.

    /** Every row including the trash. Used to recognise the database state after a restore. */
    @Query("SELECT * FROM files")
    suspend fun getAll(): List<FileEntity>

    /** Rows that are not in the trash. */
    @Query("SELECT * FROM files WHERE deletedAt IS NULL")
    suspend fun getAllActive(): List<FileEntity>

    @Insert
    suspend fun insertAll(files: List<FileEntity>)

    @Query("DELETE FROM files")
    suspend fun deleteAll()
}
