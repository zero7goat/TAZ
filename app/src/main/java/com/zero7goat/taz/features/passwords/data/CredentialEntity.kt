package com.zero7goat.taz.features.passwords.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zero7goat.taz.core.database.FolderEntity

/**
 * A saved login.
 *
 * [serviceName], [websiteUrl] and [domain] are plaintext because search needs them. Username,
 * password and notes exist only as authenticated ciphertext (see AesGcmFieldCipher for the
 * format). If the folder is deleted the entry is kept and moves to the top level.
 */
@Entity(
    tableName = "credentials",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["folderId"])]
)
data class CredentialEntity(
    @PrimaryKey val id: String,
    val folderId: String?,
    val serviceName: String,
    val websiteUrl: String,
    val domain: String,
    val usernameCipher: ByteArray,
    val passwordCipher: ByteArray,
    val noteCipher: ByteArray,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    // Ciphertext is never printed.
    override fun toString(): String = "CredentialEntity(id=$id)"
}

/** The columns the list needs. Deliberately excludes the password and note ciphertext. */
data class CredentialListRow(
    val id: String,
    val folderId: String?,
    val serviceName: String,
    val websiteUrl: String,
    val domain: String,
    val usernameCipher: ByteArray,
    val isFavorite: Boolean,
    val updatedAt: Long
) {
    override fun toString(): String = "CredentialListRow(id=$id)"
}
