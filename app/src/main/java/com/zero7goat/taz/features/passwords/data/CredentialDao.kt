package com.zero7goat.taz.features.passwords.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.zero7goat.taz.core.database.TrashRow
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    /** Inserts the entry, or replaces it if the id already exists. */
    @Upsert
    suspend fun upsert(entity: CredentialEntity)

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun getById(id: String): CredentialEntity?

    /** List data only: no password or note ciphertext is loaded. Alphabetical by name. */
    @Query(
        "SELECT id, folderId, serviceName, websiteUrl, domain, usernameCipher, isFavorite, updatedAt " +
            "FROM credentials WHERE deletedAt IS NULL ORDER BY serviceName COLLATE NOCASE ASC"
    )
    fun observeListRows(): Flow<List<CredentialListRow>>

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun deleteById(id: String)

    // Favorite and folder changes deliberately leave updatedAt untouched.
    @Query("UPDATE credentials SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE credentials SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?)

    // Trash. Moving to the trash only sets deletedAt; nothing is removed until the login is purged.

    @Query("UPDATE credentials SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun moveToTrash(id: String, deletedAt: Long)

    @Query("UPDATE credentials SET deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: String)

    @Query("SELECT id, serviceName AS label, deletedAt FROM credentials WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<TrashRow>>

    @Query("SELECT id FROM credentials WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun trashedBefore(cutoff: Long): List<String>

    @Query("SELECT id FROM credentials WHERE deletedAt IS NOT NULL")
    suspend fun trashedIds(): List<String>

    // Used only by backup, restore, export and the password health check.

    /** Every row including the trash. Used to recognise the database state after a restore. */
    @Query("SELECT * FROM credentials")
    suspend fun getAll(): List<CredentialEntity>

    /** Rows that are not in the trash. */
    @Query("SELECT * FROM credentials WHERE deletedAt IS NULL")
    suspend fun getAllActive(): List<CredentialEntity>

    @Insert
    suspend fun insertAll(entities: List<CredentialEntity>)

    @Query("DELETE FROM credentials")
    suspend fun deleteAll()
}
