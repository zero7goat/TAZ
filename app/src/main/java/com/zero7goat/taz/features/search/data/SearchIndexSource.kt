package com.zero7goat.taz.features.search.data

import com.zero7goat.taz.core.database.FolderEntity
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.CustomFields
import com.zero7goat.taz.core.fields.FieldType
import com.zero7goat.taz.core.security.FieldCipher
import com.zero7goat.taz.core.tags.Tag
import com.zero7goat.taz.core.tags.TagTarget
import com.zero7goat.taz.features.files.domain.fileCategory
import com.zero7goat.taz.features.files.domain.formatFileSize
import com.zero7goat.taz.features.files.domain.keywordList
import com.zero7goat.taz.features.notes.data.NoteRepository
import com.zero7goat.taz.features.passwords.data.CredentialRepository
import com.zero7goat.taz.features.passwords.data.toCategory
import com.zero7goat.taz.features.passwords.domain.Categories
import com.zero7goat.taz.features.passwords.domain.LoginCategory
import com.zero7goat.taz.features.search.domain.ResultType
import com.zero7goat.taz.features.search.domain.SearchDoc
import com.zero7goat.taz.features.search.domain.SearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/** Everything the search screen needs, built in memory while the screen is open. */
class SearchIndex(
    val docs: List<SearchDoc>,
    val folders: List<FolderEntity>,
    val tags: List<Tag>,
    val categories: List<LoginCategory>
) {
    val parentOf: Map<String, String?> = folders.associate { it.id to it.parentId }

    override fun toString(): String = "SearchIndex(${docs.size} items)"
}

/**
 * Builds the search index from the database and keeps it current. Login usernames, notes and custom
 * fields are decrypted here, in memory only. What may never appear in results is left out:
 * passwords are not read at all, and hidden custom field values are blanked (their labels stay).
 *
 * The flow is cold: the index exists only while the search screen collects it, and the screen is
 * removed (and the index with it) as soon as the app locks.
 */
class SearchIndexSource(private val database: TazDatabase, cipher: FieldCipher) {

    private val credentials = CredentialRepository(database.credentialDao(), cipher)
    private val notes = NoteRepository(database.noteDao(), cipher = cipher)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun observe(): Flow<SearchIndex> {
        val tables = combine(
            database.noteDao().observeAll(),
            database.credentialDao().observeListRows(),
            database.fileDao().observeAll(),
            database.folderDao().observeAll(),
            database.tagDao().observeLinks()
        ) { noteRows, loginRows, fileRows, folders, links -> Tables(noteRows, loginRows, fileRows, folders, links) }

        return combine(tables, database.tagDao().observeAll(), database.categoryDao().observeAll()) { t, tags, custom ->
            Triple(t, tags, custom)
        }
            .debounce(120) // a bulk change (moving 50 items) rebuilds once, not 50 times
            .mapLatest { (t, tagRows, customCategories) ->
                build(t, tagRows.map { Tag(it.id, it.name) }, Categories.builtIn + customCategories.map { it.toCategory() })
            }
            .flowOn(Dispatchers.Default)
    }

    private class Tables(
        val notes: List<com.zero7goat.taz.features.notes.data.NoteEntity>,
        val logins: List<com.zero7goat.taz.features.passwords.data.CredentialListRow>,
        val files: List<com.zero7goat.taz.features.files.data.FileEntity>,
        val folders: List<FolderEntity>,
        val links: List<com.zero7goat.taz.core.tags.ItemTagRow>
    )

    private suspend fun build(t: Tables, tags: List<Tag>, categories: List<LoginCategory>): SearchIndex {
        val tagsByItem = t.links.groupBy({ it.itemType to it.itemId }, { Tag(it.tagId, it.name) })
        fun tagsOf(target: TagTarget, id: String) = tagsByItem[target.key to id].orEmpty()

        val noteFields = notes.loadAllFields().first
        val loginSecrets = credentials.decryptForSearch().associateBy { it.id }
        val docs = ArrayList<SearchDoc>(t.notes.size + t.logins.size + t.files.size + t.folders.size)

        t.notes.forEach { note ->
            val itemTags = tagsOf(TagTarget.NOTE, note.id)
            val body = IMAGE_REFERENCE.replace(note.body, "[image]")
            docs += SearchDoc(
                type = ResultType.NOTE,
                id = note.id,
                title = note.title.ifBlank { "Untitled" },
                fields = buildList {
                    if (note.description.isNotBlank()) add(SearchField("Description", note.description))
                    if (body.isNotBlank()) add(SearchField("Text", body))
                    addAll(fieldEntries(noteFields[note.id].orEmpty()))
                },
                tagIds = itemTags.mapTo(HashSet()) { it.id },
                tagNames = itemTags.map { it.name },
                folderId = note.folderId,
                isFavorite = note.isFavorite,
                updatedAt = note.updatedAt,
                summary = note.description.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty() }.take(120),
                hint = note.colorIndex.toString()
            )
        }

        t.logins.forEach { row ->
            val itemTags = tagsOf(TagTarget.LOGIN, row.id)
            val secret = loginSecrets[row.id]
            val category = Categories.resolve(row.category, categories)
            docs += SearchDoc(
                type = ResultType.LOGIN,
                id = row.id,
                title = row.serviceName,
                fields = buildList {
                    if (!secret?.username.isNullOrEmpty()) add(SearchField("Username", secret!!.username))
                    if (row.websiteUrl.isNotBlank()) add(SearchField("Website", row.websiteUrl))
                    add(SearchField("Category", category.name))
                    secret?.fields?.let { addAll(fieldEntries(it)) }
                    if (!secret?.notes.isNullOrBlank()) add(SearchField("Notes", secret!!.notes))
                },
                tagIds = itemTags.mapTo(HashSet()) { it.id },
                tagNames = itemTags.map { it.name },
                folderId = row.folderId,
                isFavorite = row.isFavorite,
                updatedAt = row.updatedAt,
                summary = secret?.username?.takeIf { it.isNotEmpty() } ?: category.name,
                hint = row.category
            )
        }

        t.files.forEach { file ->
            val itemTags = tagsOf(TagTarget.FILE, file.id)
            docs += SearchDoc(
                type = ResultType.FILE,
                id = file.id,
                title = file.displayName,
                fields = buildList {
                    if (file.originalName != file.displayName) add(SearchField("Original name", file.originalName))
                    if (file.description.isNotBlank()) add(SearchField("Description", file.description))
                    val keywords = keywordList(file.keywords)
                    if (keywords.isNotEmpty()) add(SearchField("Keywords", keywords.joinToString(", ")))
                },
                tagIds = itemTags.mapTo(HashSet()) { it.id },
                tagNames = itemTags.map { it.name },
                folderId = file.folderId,
                isFavorite = file.isFavorite,
                updatedAt = file.updatedAt,
                summary = "${fileCategory(file.mimeType, file.displayName).label.removeSuffix("s")} · ${formatFileSize(file.sizeBytes)}",
                hint = file.mimeType
            )
        }

        t.folders.forEach { folder ->
            docs += SearchDoc(
                type = ResultType.FOLDER,
                id = folder.id,
                title = folder.name,
                fields = emptyList(),
                tagIds = emptySet(),
                tagNames = emptyList(),
                folderId = folder.parentId,
                isFavorite = false,
                updatedAt = folder.createdAt,
                summary = when (folder.module) {
                    "NOTES" -> "Notes folder"
                    "PASSWORDS" -> "Passwords folder"
                    else -> "Files folder"
                },
                hint = folder.module
            )
        }
        return SearchIndex(docs, t.folders, tags, categories)
    }

    /** Custom fields as search text: labels always, values only when the field is not hidden. */
    private fun fieldEntries(fields: List<CustomField>): List<SearchField> = fields.mapNotNull { field ->
        val label = field.label.ifBlank { field.type.label }
        val value = when {
            !CustomFields.isSearchable(field) -> ""
            field.type == FieldType.TOGGLE -> ""
            else -> field.value
        }
        if (value.isEmpty() && field.label.isBlank()) null else SearchField(label, if (value.isEmpty()) label else "$label: $value")
    }

    private companion object {
        val IMAGE_REFERENCE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    }
}
