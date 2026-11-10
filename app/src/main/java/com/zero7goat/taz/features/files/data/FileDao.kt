package com.zero7goat.taz.features.files.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {

    @Insert
    suspend fun insert(file: FileEntity)

    @Query("SELECT * FROM files ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FileEntity>>

    @Query("SELECT * FROM files WHERE id = :id")
    fun observeById(id: String): Flow<FileEntity?>

    @Query("SELECT * FROM files WHERE id = :id")
    suspend fun getById(id: String): FileEntity?

    /** Names already used in a folder (null means the top level). */
    @Query("SELECT id, displayName FROM files WHERE folderId IS :folderId")
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

    // Used only by backup and restore.

    @Query("SELECT * FROM files")
    suspend fun getAll(): List<FileEntity>

    @Insert
    suspend fun insertAll(files: List<FileEntity>)

    @Query("DELETE FROM files")
    suspend fun deleteAll()
}
