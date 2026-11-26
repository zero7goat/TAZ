package com.zero7goat.taz.features.files.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zero7goat.taz.core.database.FolderEntity

/**
 * Metadata of a stored file. The file's contents are never in the database: they are a copy in
 * the app's private storage at [storedPath] (relative to the app files directory).
 *
 * [originalName] is the name the file had when imported; [displayName] is what the user sees and
 * can rename. [keywords] holds normalized keywords separated by commas. [createdAt] is when the
 * file was added to TAZ and [updatedAt] when its details were last edited. If the folder is
 * deleted the file is kept and moves to the top level. The stored copy is not encrypted.
 */
@Entity(
    tableName = "files",
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
data class FileEntity(
    @PrimaryKey val id: String,
    val folderId: String?,
    val originalName: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storedPath: String,
    val keywords: String,
    val description: String,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** When the file was moved to the trash (epoch ms), or null if it is not in the trash. */
    val deletedAt: Long? = null,
    /** Asks for the passcode again before the file opens, even while TAZ is unlocked. */
    @ColumnInfo(defaultValue = "0")
    val isLocked: Boolean = false,
    /** How the stored copy is kept: 0 = plain (before 2.0), 1 = encrypted (see VaultFileCipher). */
    @ColumnInfo(defaultValue = "0")
    val encryption: Int = 0
)

/** Just enough to pick a free name inside a folder. */
data class FileNameRow(
    val id: String,
    val displayName: String
)
