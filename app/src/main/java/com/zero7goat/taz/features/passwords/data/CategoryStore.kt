package com.zero7goat.taz.features.passwords.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.zero7goat.taz.features.passwords.domain.Categories
import com.zero7goat.taz.features.passwords.domain.CategoryIcon
import com.zero7goat.taz.features.passwords.domain.FieldTemplate
import com.zero7goat.taz.features.passwords.domain.LoginCategory
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A category the user created. [fieldsJson] is the field template: labels and types only, never
 * values, so it holds nothing secret.
 */
@Entity(tableName = "credential_categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val fieldsJson: String,
    val createdAt: Long
)

@Dao
interface CategoryDao {

    @Query("SELECT * FROM credential_categories ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM credential_categories")
    suspend fun getAll(): List<CategoryEntity>

    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Query("DELETE FROM credential_categories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE credentials SET category = :newKey WHERE category = :oldKey")
    suspend fun recategorize(oldKey: String, newKey: String)

    /** Deletes a custom category. Its entries keep all their fields and become "Other". */
    @Transaction
    suspend fun deleteKeepingEntries(id: String) {
        recategorize(Categories.customKey(id), Categories.OTHER.key)
        deleteById(id)
    }

    @Insert
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM credential_categories")
    suspend fun deleteAll()
}

fun CategoryEntity.toCategory() = LoginCategory(
    key = Categories.customKey(id),
    name = name,
    icon = CategoryIcon.fromKey(icon),
    usesLoginFields = false,
    template = Categories.decodeTemplate(fieldsJson),
    isBuiltIn = false
)

class CategoryRepository(private val dao: CategoryDao, private val now: () -> Long = { System.currentTimeMillis() }) {

    fun observeCustom(): Flow<List<LoginCategory>> = dao.observeAll().map { list -> list.map { it.toCategory() } }

    /** Built-in categories first, then the user's own. */
    fun observeAll(): Flow<List<LoginCategory>> = observeCustom().map { Categories.builtIn + it }

    /** Creates or updates a custom category. [key] is null for a new one. Returns its key. */
    suspend fun save(key: String?, name: String, icon: CategoryIcon, template: List<FieldTemplate>): String? {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(40)
        if (clean.isEmpty()) return null
        val id = key?.takeIf { Categories.isCustomKey(it) }?.removePrefix(Categories.CUSTOM_PREFIX)
            ?: UUID.randomUUID().toString()
        val existing = dao.getAll().firstOrNull { it.id == id }
        dao.upsert(
            CategoryEntity(
                id = id,
                name = clean,
                icon = icon.key,
                fieldsJson = Categories.encodeTemplate(template.filter { it.label.isNotBlank() }),
                createdAt = existing?.createdAt ?: now()
            )
        )
        return Categories.customKey(id)
    }

    suspend fun delete(key: String) {
        if (!Categories.isCustomKey(key)) return
        dao.deleteKeepingEntries(key.removePrefix(Categories.CUSTOM_PREFIX))
    }
}
