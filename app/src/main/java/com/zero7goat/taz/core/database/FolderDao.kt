package com.zero7goat.taz.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders WHERE module = :module ORDER BY name ASC")
    fun observeByModule(module: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE module = :module ORDER BY name ASC")
    suspend fun getByModule(module: String): List<FolderEntity>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    // Deleting a folder without losing anything: its subfolders and items move up one level.

    @Query("UPDATE folders SET parentId = :newParentId WHERE parentId = :folderId")
    suspend fun reparentSubfolders(folderId: String, newParentId: String?)

    @Query("UPDATE notes SET folderId = :newFolderId WHERE folderId = :folderId")
    suspend fun moveNotesOut(folderId: String, newFolderId: String?)

    @Query("UPDATE credentials SET folderId = :newFolderId WHERE folderId = :folderId")
    suspend fun moveCredentialsOut(folderId: String, newFolderId: String?)

    @Query("UPDATE files SET folderId = :newFolderId WHERE folderId = :folderId")
    suspend fun moveFilesOut(folderId: String, newFolderId: String?)

    /**
     * Deletes [folder] but keeps everything in it: its subfolders, notes, logins and files (also
     * those in the trash) move to the folder's parent, or to the top level. All in one transaction,
     * so an interruption leaves either the old state or the new one, never something in between.
     */
    @Transaction
    suspend fun deleteKeepingContents(folder: FolderEntity) {
        val parent = folder.parentId
        reparentSubfolders(folder.id, parent)
        moveNotesOut(folder.id, parent)
        moveCredentialsOut(folder.id, parent)
        moveFilesOut(folder.id, parent)
        deleteById(folder.id)
    }

    // Used only by backup and restore.

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Insert
    suspend fun insertAll(folders: List<FolderEntity>)

    @Query("DELETE FROM folders")
    suspend fun deleteAll()
}
