package com.zero7goat.taz.features.passwords.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
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
            "FROM credentials ORDER BY serviceName COLLATE NOCASE ASC"
    )
    fun observeListRows(): Flow<List<CredentialListRow>>

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun deleteById(id: String)

    // Favorite and folder changes deliberately leave updatedAt untouched.
    @Query("UPDATE credentials SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE credentials SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?)

    // Used only by backup and restore.

    @Query("SELECT * FROM credentials")
    suspend fun getAll(): List<CredentialEntity>

    @Insert
    suspend fun insertAll(entities: List<CredentialEntity>)

    @Query("DELETE FROM credentials")
    suspend fun deleteAll()
}
