package com.zero7goat.taz.features.passwords.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zero7goat.taz.core.database.FolderEntity

/**
 * A saved login (or another kind of entry, see [category]).
 *
 * [serviceName], [websiteUrl] and [domain] are plaintext because search needs them. Username,
 * password, notes and custom fields exist only as authenticated ciphertext (see AesGcmFieldCipher
 * for the format). If the folder is deleted the entry is kept and moves to the top level.
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
    val updatedAt: Long,
    /** When the login was moved to the trash (epoch ms), or null if it is not in the trash. */
    val deletedAt: Long? = null,
    /** A built-in category key, or "custom:" plus a custom category id. Entries from before 1.2 are logins. */
    @ColumnInfo(defaultValue = "login")
    val category: String = "login",
    /** The custom fields as one encrypted blob, or null when there are none. */
    val fieldsCipher: ByteArray? = null
) {
    // Ciphertext is never printed.
    override fun toString(): String = "CredentialEntity(id=$id)"
}

/** The columns the list needs. Deliberately excludes the password, note and field ciphertext. */
data class CredentialListRow(
    val id: String,
    val folderId: String?,
    val serviceName: String,
    val websiteUrl: String,
    val domain: String,
    val usernameCipher: ByteArray,
    val isFavorite: Boolean,
    val updatedAt: Long,
    val category: String,
    val createdAt: Long
) {
    override fun toString(): String = "CredentialListRow(id=$id)"
}
