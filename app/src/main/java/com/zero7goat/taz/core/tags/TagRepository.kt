package com.zero7goat.taz.core.tags

import android.database.sqlite.SQLiteConstraintException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A tag as the UI uses it. */
data class Tag(val id: String, val name: String)

/** Result of creating or renaming a tag. */
sealed interface TagResult {
    data class Ok(val tag: Tag) : TagResult
    data object Blank : TagResult

    /** Another tag already has this name (ignoring case and spaces). */
    data class Duplicate(val existing: Tag) : TagResult
}

class TagRepository(private val dao: TagDao, private val now: () -> Long = { System.currentTimeMillis() }) {

    fun observeTags(): Flow<List<Tag>> = dao.observeAll().map { list -> list.map { Tag(it.id, it.name) } }

    fun observeUsage(): Flow<List<TagUsageRow>> = dao.observeWithUsage()

    /** Tags of every item of [target], by item id. */
    fun observeTagsByItem(target: TagTarget): Flow<Map<String, List<Tag>>> =
        dao.observeLinks(target.key).map { rows ->
            rows.groupBy({ it.itemId }, { Tag(it.tagId, it.name) })
        }

    /** Every link, keyed by target and item id. */
    fun observeAllLinks(): Flow<Map<Pair<TagTarget, String>, List<Tag>>> =
        dao.observeLinks().map { rows ->
            rows.mapNotNull { row -> TagTarget.fromKey(row.itemType)?.let { (it to row.itemId) to Tag(row.tagId, row.name) } }
                .groupBy({ it.first }, { it.second })
        }

    fun observeItemTags(target: TagTarget, itemId: String): Flow<List<Tag>> =
        dao.observeItem(target.key, itemId).map { rows -> rows.map { Tag(it.tagId, it.name) } }

    /** Creates a tag, or returns the existing one with the same name (ignoring case and spaces). */
    suspend fun createOrGet(name: String): TagResult {
        val clean = TagNames.clean(name)
        if (clean.isEmpty()) return TagResult.Blank
        val key = TagNames.normalize(clean)
        dao.findByNormalizedName(key)?.let { return TagResult.Ok(Tag(it.id, it.name)) }
        val entity = TagEntity(UUID.randomUUID().toString(), clean, key, now())
        return try {
            dao.insert(entity)
            TagResult.Ok(Tag(entity.id, entity.name))
        } catch (e: SQLiteConstraintException) {
            // Created at the same moment elsewhere: use that one.
            dao.findByNormalizedName(key)?.let { TagResult.Ok(Tag(it.id, it.name)) } ?: TagResult.Blank
        }
    }

    /** Renames a tag. Changing only the capitalisation is allowed; taking another tag's name is not. */
    suspend fun rename(id: String, name: String): TagResult {
        val clean = TagNames.clean(name)
        if (clean.isEmpty()) return TagResult.Blank
        val key = TagNames.normalize(clean)
        val clash = dao.findByNormalizedName(key)
        if (clash != null && clash.id != id) return TagResult.Duplicate(Tag(clash.id, clash.name))
        dao.rename(id, clean, key)
        return TagResult.Ok(Tag(id, clean))
    }

    /** Deletes the tag and its links. The tagged notes, logins and files are not affected. */
    suspend fun delete(id: String) = dao.delete(id)

    suspend fun setItemTags(target: TagTarget, itemId: String, tagIds: Collection<String>) =
        dao.setItemTags(target.key, itemId, tagIds.distinct())

    suspend fun addTag(target: TagTarget, itemId: String, tagId: String) =
        dao.insertLinks(listOf(ItemTagEntity(tagId, target.key, itemId)))

    suspend fun removeTag(target: TagTarget, itemId: String, tagId: String) =
        dao.deleteLink(target.key, itemId, tagId)

    /** Adds [tagIds] to every item in [itemIds], keeping the tags they already have. */
    suspend fun addTags(target: TagTarget, itemIds: Collection<String>, tagIds: Collection<String>) {
        if (itemIds.isEmpty() || tagIds.isEmpty()) return
        dao.insertLinks(itemIds.flatMap { item -> tagIds.map { ItemTagEntity(it, target.key, item) } })
    }

    /** Called when an item is permanently deleted. */
    suspend fun forgetItem(target: TagTarget, itemId: String) = dao.deleteLinksOf(target.key, itemId)

    suspend fun cleanupOrphans(): Int = dao.deleteOrphanLinks()
}
