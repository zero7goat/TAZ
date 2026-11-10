package com.zero7goat.taz.features.notes.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zero7goat.taz.core.database.FolderEntity

/**
 * A note. [folderId] is null for a note at the top level. If its folder is deleted the
 * note is kept and moves to the top level. [colorIndex] selects one of the note colors
 * (0 = none). Timestamps are epoch milliseconds (UTC). [body] is plain text that may
 * contain Markdown-style formatting.
 */
@Entity(
    tableName = "notes",
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
data class NoteEntity(
    @PrimaryKey val id: String,
    val folderId: String?,
    val title: String,
    val description: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean,
    val isPinned: Boolean,
    val colorIndex: Int,
    /** When the note was moved to the trash (epoch ms), or null if it is not in the trash. */
    val deletedAt: Long? = null
)
