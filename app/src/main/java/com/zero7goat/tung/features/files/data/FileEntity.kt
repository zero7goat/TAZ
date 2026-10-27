package com.zero7goat.tung.features.files.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zero7goat.tung.core.database.FolderEntity

/**
 * Metadata of a stored file. The file's contents are never in the database: they are a copy in
 * the app's private storage at [storedPath] (relative to the app files directory).
 *
 * [originalName] is the name the file had when imported; [displayName] is what the user sees and
 * can rename. [keywords] holds normalized keywords separated by commas. [createdAt] is when the
 * file was added to TUNG and [updatedAt] when its details were last edited. If the folder is
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
    val updatedAt: Long
)

/** Just enough to pick a free name inside a folder. */
data class FileNameRow(
    val id: String,
    val displayName: String
)
