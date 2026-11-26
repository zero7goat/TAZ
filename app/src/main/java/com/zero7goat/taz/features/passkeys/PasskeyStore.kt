package com.zero7goat.taz.features.passkeys

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A passkey made by TAZ. [rpId] (the website, like "example.com") and [userName] are plain so the
 * right passkeys can be offered; the private key exists only as Keystore ciphertext
 * ([privateKeyCipher], associated data "taz.passkey.v1|id|key").
 */
@Entity(tableName = "passkeys", indices = [Index(value = ["credentialId"], unique = true), Index(value = ["rpId"])])
data class PasskeyEntity(
    @PrimaryKey val id: String,
    /** The WebAuthn credential id, Base64url. */
    val credentialId: String,
    val rpId: String,
    val userName: String,
    val userDisplayName: String,
    /** The site's user handle, Base64url. */
    val userHandle: String,
    val privateKeyCipher: ByteArray,
    val signCount: Long,
    val createdAt: Long,
    val lastUsedAt: Long
) {
    override fun toString(): String = "PasskeyEntity(id=$id, rpId=$rpId)"
}

@Dao
interface PasskeyDao {
    @Upsert
    suspend fun upsert(passkey: PasskeyEntity)

    @Query("SELECT * FROM passkeys WHERE rpId = :rpId ORDER BY lastUsedAt DESC")
    suspend fun forRp(rpId: String): List<PasskeyEntity>

    @Query("SELECT * FROM passkeys WHERE credentialId = :credentialId")
    suspend fun byCredentialId(credentialId: String): PasskeyEntity?

    @Query("SELECT * FROM passkeys ORDER BY rpId COLLATE NOCASE")
    fun observeAll(): Flow<List<PasskeyEntity>>

    @Query("SELECT * FROM passkeys")
    suspend fun getAll(): List<PasskeyEntity>

    @Query("UPDATE passkeys SET signCount = :count, lastUsedAt = :at WHERE id = :id")
    suspend fun markUsed(id: String, count: Long, at: Long)

    @Query("DELETE FROM passkeys WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM passkeys")
    suspend fun deleteAll()
}
