package com.zero7goat.taz.core.tags

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tag. [name] is what the user sees; [normalizedName] (trimmed, single spaces, lower case) is
 * unique, so "Work", " work " and "WORK" can never become three different tags.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val createdAt: Long
)

/**
 * Links a tag to a note, login or file. Deleting a tag removes only its links (cascade), never the
 * items. Items have no foreign key here (three different tables), so links of an item are removed
 * when the item is permanently deleted, and orphaned links are cleaned up at start.
 */
@Entity(
    tableName = "item_tags",
    primaryKeys = ["tagId", "itemType", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["itemType", "itemId"]), Index(value = ["tagId"])]
)
data class ItemTagEntity(
    val tagId: String,
    val itemType: String,
    val itemId: String
)

/** What a tag is attached to. [key] is stored and must never change. */
enum class TagTarget(val key: String) {
    NOTE("NOTE"), LOGIN("LOGIN"), FILE("FILE");

    companion object {
        fun fromKey(key: String): TagTarget? = entries.firstOrNull { it.key == key }
    }
}

/** A tag with how many items (not in the trash) use it. */
data class TagUsageRow(
    val id: String,
    val name: String,
    val normalizedName: String,
    val createdAt: Long,
    val usage: Int
)

/** One link, joined with the tag's name, for showing chips. */
data class ItemTagRow(
    val itemType: String,
    val itemId: String,
    val tagId: String,
    val name: String
)
