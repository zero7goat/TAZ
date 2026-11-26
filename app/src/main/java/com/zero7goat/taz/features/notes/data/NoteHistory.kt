package com.zero7goat.taz.features.notes.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * An earlier version of a note, saved when the note changes. Removed with the note. Kept on the
 * device only (backups carry the current version of each note).
 */
@Entity(
    tableName = "note_versions",
    foreignKeys = [
        ForeignKey(entity = NoteEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["noteId"])]
)
data class NoteVersionEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val title: String,
    val description: String,
    val body: String,
    /** When this version was replaced by a newer one. */
    val savedAt: Long
)

/** A note template the user made. Built-in templates live in code (NoteTemplates). */
@Entity(tableName = "note_templates")
data class NoteTemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val title: String,
    val body: String,
    val createdAt: Long
)

@Dao
interface NoteHistoryDao {
    @Insert
    suspend fun insertVersion(version: NoteVersionEntity)

    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY savedAt DESC")
    fun observeVersions(noteId: String): Flow<List<NoteVersionEntity>>

    @Query("SELECT * FROM note_versions WHERE noteId = :noteId ORDER BY savedAt DESC LIMIT 1")
    suspend fun latestVersion(noteId: String): NoteVersionEntity?

    /** Keeps the newest [keep] versions of a note. */
    @Query(
        "DELETE FROM note_versions WHERE noteId = :noteId AND id NOT IN " +
            "(SELECT id FROM note_versions WHERE noteId = :noteId ORDER BY savedAt DESC LIMIT :keep)"
    )
    suspend fun trim(noteId: String, keep: Int)

    @Query("DELETE FROM note_versions WHERE id = :id")
    suspend fun deleteVersion(id: String)

    @Query("DELETE FROM note_versions")
    suspend fun deleteAllVersions()

    @Query("SELECT * FROM note_templates ORDER BY name COLLATE NOCASE")
    fun observeTemplates(): Flow<List<NoteTemplateEntity>>

    @Query("SELECT * FROM note_templates")
    suspend fun getAllTemplates(): List<NoteTemplateEntity>

    @Upsert
    suspend fun upsertTemplate(template: NoteTemplateEntity)

    @Query("DELETE FROM note_templates WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    @Query("DELETE FROM note_templates")
    suspend fun deleteAllTemplates()
}
