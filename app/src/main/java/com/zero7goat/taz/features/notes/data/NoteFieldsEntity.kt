package com.zero7goat.taz.features.notes.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * The custom fields of a note, encrypted as one blob with the device Keystore key (same format as
 * login fields). Kept in their own table so the notes table and its rows are not changed. Removed
 * together with the note when the note is permanently deleted (cascade).
 */
@Entity(
    tableName = "note_fields",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
class NoteFieldsEntity(
    @PrimaryKey val noteId: String,
    val fieldsCipher: ByteArray
) {
    override fun toString(): String = "NoteFieldsEntity(noteId=$noteId)"
}
