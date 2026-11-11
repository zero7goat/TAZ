package com.zero7goat.taz.core.tags

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    /** Every tag with the number of items outside the trash that use it, alphabetical. */
    @Query(
        "SELECT t.id, t.name, t.normalizedName, t.createdAt, " +
            "(SELECT COUNT(*) FROM item_tags it WHERE it.tagId = t.id AND (" +
            "(it.itemType = 'NOTE' AND it.itemId IN (SELECT id FROM notes WHERE deletedAt IS NULL)) OR " +
            "(it.itemType = 'LOGIN' AND it.itemId IN (SELECT id FROM credentials WHERE deletedAt IS NULL)) OR " +
            "(it.itemType = 'FILE' AND it.itemId IN (SELECT id FROM files WHERE deletedAt IS NULL)))) AS usage " +
            "FROM tags t ORDER BY t.name COLLATE NOCASE ASC"
    )
    fun observeWithUsage(): Flow<List<TagUsageRow>>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TagEntity)

    @Query("UPDATE tags SET name = :name, normalizedName = :normalizedName WHERE id = :id")
    suspend fun rename(id: String, name: String, normalizedName: String)

    /** Deletes the tag; its links go with it (cascade). Tagged items are not touched. */
    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: String)

    /** Every link with its tag name. */
    @Query(
        "SELECT it.itemType, it.itemId, it.tagId, t.name FROM item_tags it " +
            "JOIN tags t ON t.id = it.tagId ORDER BY t.name COLLATE NOCASE ASC"
    )
    fun observeLinks(): Flow<List<ItemTagRow>>

    @Query(
        "SELECT it.itemType, it.itemId, it.tagId, t.name FROM item_tags it " +
            "JOIN tags t ON t.id = it.tagId WHERE it.itemType = :itemType ORDER BY t.name COLLATE NOCASE ASC"
    )
    fun observeLinks(itemType: String): Flow<List<ItemTagRow>>

    @Query(
        "SELECT it.itemType, it.itemId, it.tagId, t.name FROM item_tags it " +
            "JOIN tags t ON t.id = it.tagId WHERE it.itemType = :itemType AND it.itemId = :itemId " +
            "ORDER BY t.name COLLATE NOCASE ASC"
    )
    fun observeItem(itemType: String, itemId: String): Flow<List<ItemTagRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<ItemTagEntity>)

    @Query("DELETE FROM item_tags WHERE itemType = :itemType AND itemId = :itemId")
    suspend fun deleteLinksOf(itemType: String, itemId: String)

    @Query("DELETE FROM item_tags WHERE itemType = :itemType AND itemId = :itemId AND tagId = :tagId")
    suspend fun deleteLink(itemType: String, itemId: String, tagId: String)

    /** Replaces the tags of one item in a single transaction. */
    @Transaction
    suspend fun setItemTags(itemType: String, itemId: String, tagIds: Collection<String>) {
        deleteLinksOf(itemType, itemId)
        if (tagIds.isNotEmpty()) insertLinks(tagIds.map { ItemTagEntity(it, itemType, itemId) })
    }

    /** Removes links whose item no longer exists at all (it was permanently deleted). */
    @Query(
        "DELETE FROM item_tags WHERE " +
            "(itemType = 'NOTE' AND itemId NOT IN (SELECT id FROM notes)) OR " +
            "(itemType = 'LOGIN' AND itemId NOT IN (SELECT id FROM credentials)) OR " +
            "(itemType = 'FILE' AND itemId NOT IN (SELECT id FROM files)) OR " +
            "itemType NOT IN ('NOTE', 'LOGIN', 'FILE')"
    )
    suspend fun deleteOrphanLinks(): Int

    // Used only by backup and restore.

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM item_tags")
    suspend fun getAllLinks(): List<ItemTagEntity>

    @Insert
    suspend fun insertAll(tags: List<TagEntity>)

    @Insert
    suspend fun insertAllLinks(links: List<ItemTagEntity>)

    @Query("DELETE FROM item_tags")
    suspend fun deleteAllLinks()

    @Query("DELETE FROM tags")
    suspend fun deleteAll()
}
