package com.zero7goat.tung.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders WHERE module = :module ORDER BY name ASC")
    fun observeByModule(module: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE module = :module ORDER BY name ASC")
    suspend fun getByModule(module: String): List<FolderEntity>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    // Used only by backup and restore.

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Insert
    suspend fun insertAll(folders: List<FolderEntity>)

    @Query("DELETE FROM folders")
    suspend fun deleteAll()
}
