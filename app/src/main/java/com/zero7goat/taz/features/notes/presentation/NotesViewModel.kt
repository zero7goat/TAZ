package com.zero7goat.taz.features.notes.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zero7goat.taz.core.database.FolderEntity
import com.zero7goat.taz.core.database.FolderModule
import com.zero7goat.taz.core.database.FolderRepository
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.CustomFields
import com.zero7goat.taz.core.security.FieldCipherException
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import com.zero7goat.taz.core.tags.Tag
import com.zero7goat.taz.core.tags.TagRepository
import com.zero7goat.taz.core.tags.TagTarget
import com.zero7goat.taz.features.notes.data.NoteFieldsLoad
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.zero7goat.taz.features.notes.data.NoteEntity
import com.zero7goat.taz.features.notes.data.NoteImageStore
import com.zero7goat.taz.features.notes.data.NoteRepository
import com.zero7goat.taz.features.notes.domain.NoteSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the notes screen shows.
 *
 * While [isBrowsing] (no search, no favorites filter), [folders] and [notes] are the
 * contents of [currentFolder] (or the top level). Otherwise they are results across all folders.
 */
data class NotesUiState(
    val notes: List<NoteEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val allFolders: List<FolderEntity> = emptyList(),
    val currentFolder: FolderEntity? = null,
    val path: List<FolderEntity> = emptyList(),
    val isBrowsing: Boolean = true,
    val isLoading: Boolean = true,
    val hasContent: Boolean = false,
    val favoritesOnly: Boolean = false,
    /** The search text (trimmed), used to show why a note matched. Empty when not searching. */
    val query: String = "",
    /** Tags of each note, by id. */
    val tags: Map<String, List<Tag>> = emptyMap()
)

/** The note currently open in the editor. [noteId] is null when no editor is open. */
data class EditorUiState(
    val noteId: String? = null,
    val folderId: String? = null,
    val isLoaded: Boolean = false,
    val isNewNote: Boolean = true,
    val isDirty: Boolean = false,
    val createdAt: Long = 0L,
    val title: String = "",
    val description: String = "",
    val body: String = "",
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    val colorIndex: Int = 0,
    /** Custom fields, decrypted. Only kept in memory. */
    val fields: List<CustomField> = emptyList(),
    /** False when stored fields exist but cannot be decrypted: they are then never overwritten. */
    val fieldsReadable: Boolean = true
) {
    val isBlank: Boolean
        get() = title.isBlank() && description.isBlank() && body.isBlank() &&
            fields.none { it.value.isNotEmpty() && it.value != CustomFields.FALSE }
}

class NotesViewModel(
    private val repository: NoteRepository,
    private val folderRepository: FolderRepository,
    private val appContext: Context,
    private val imageStore: NoteImageStore,
    tagRepository: TagRepository? = null
) : ViewModel() {

    private val tagsByNote: Flow<Map<String, List<Tag>>> =
        tagRepository?.observeTagsByItem(TagTarget.NOTE) ?: flowOf(emptyMap())

    init {
        // Images inserted into notes and later removed are cleaned up in the background.
        viewModelScope.launch { runCatching { repository.cleanupUnusedImages() } }
    }

    // ---------- list and folders ----------

    private val query = MutableStateFlow("")
    private val favoritesOnly = MutableStateFlow(false)
    private val currentFolderId = MutableStateFlow<String?>(null)

    private val filters = combine(query, favoritesOnly, currentFolderId) { text, onlyFavorites, folderId ->
        Triple(text, onlyFavorites, folderId)
    }

    val uiState: StateFlow<NotesUiState> = combine(
        repository.observeNotes(),
        folderRepository.observeFolders(FolderModule.NOTES),
        filters,
        tagsByNote
    ) { notes, folders, (text, onlyFavorites, folderId), tags ->
        buildUiState(notes, folders, text, onlyFavorites, folderId, tags)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun setFavoritesOnly(value: Boolean) {
        favoritesOnly.value = value
    }

    /** Shows the contents of [folderId], or the top level when null. */
    fun openFolder(folderId: String?) {
        currentFolderId.value = folderId
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepository.createFolder(FolderModule.NOTES, trimmed, parentId = effectiveFolderId())
        }
    }

    fun renameFolder(folder: FolderEntity, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == folder.name) return
        viewModelScope.launch { folderRepository.updateFolder(folder.copy(name = trimmed)) }
    }

    /** Deletes the folder and its subfolders. Notes inside are kept and move to the top level. */
    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch { folderRepository.deleteFolder(folder) }
    }

    fun moveNote(note: NoteEntity, folderId: String?) {
        viewModelScope.launch { repository.setFolder(note.id, folderId) }
    }

    fun togglePinned(note: NoteEntity) {
        viewModelScope.launch { repository.setPinned(note.id, !note.isPinned) }
    }

    fun toggleFavorite(note: NoteEntity) {
        viewModelScope.launch { repository.setFavorite(note.id, !note.isFavorite) }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch { repository.deleteNote(note.id) }
    }

    // ---------- several notes at once ----------

    /** Moves the notes to the trash (restorable for 30 days), never erases them. */
    fun deleteNotes(ids: Collection<String>) {
        val targets = ids.toList()
        viewModelScope.launch { targets.forEach { repository.deleteNote(it) } }
    }

    /** Moves the notes into [folderId], or to the top level when null. */
    fun moveNotes(ids: Collection<String>, folderId: String?) {
        val targets = ids.toList()
        viewModelScope.launch { targets.forEach { repository.setFolder(it, folderId) } }
    }

    fun setFavorite(ids: Collection<String>, isFavorite: Boolean) {
        val targets = ids.toList()
        viewModelScope.launch { targets.forEach { repository.setFavorite(it, isFavorite) } }
    }

    fun setPinned(ids: Collection<String>, isPinned: Boolean) {
        val targets = ids.toList()
        viewModelScope.launch { targets.forEach { repository.setPinned(it, isPinned) } }
    }

    /** Creates a folder inside [parentId] right away and returns its id (used by the folder picker). */
    suspend fun createFolderNow(name: String, parentId: String?): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return folderRepository.createFolder(FolderModule.NOTES, trimmed, parentId).id
    }

    /** Copies a picked image into private storage. The editor then inserts a reference to it. */
    suspend fun importImage(uri: Uri): NoteImageImport = importNoteImage(appContext, imageStore, uri)

    /** The folder being browsed, or null if none or if it no longer exists. */
    private suspend fun effectiveFolderId(): String? {
        val id = currentFolderId.value ?: return null
        return if (folderRepository.getFolders(FolderModule.NOTES).any { it.id == id }) id else null
    }

    // ---------- editor ----------

    private val _editor = MutableStateFlow(EditorUiState())
    val editor: StateFlow<EditorUiState> = _editor.asStateFlow()

    // Serializes opening, saving and closing so a quick close-and-reopen cannot race.
    private val editorLock = Mutex()

    /**
     * Opens [noteId] for editing. An id with no stored note starts a new, empty note in the
     * folder currently being browsed.
     */
    fun openEditor(noteId: String) {
        viewModelScope.launch {
            editorLock.withLock {
                val current = _editor.value
                if (current.noteId == noteId && current.isLoaded) return@withLock
                val existing = repository.getNote(noteId)
                val loadedFields = if (existing != null) repository.loadFields(noteId) else NoteFieldsLoad.Ok(emptyList())
                _editor.value = if (existing != null) {
                    EditorUiState(
                        fields = (loadedFields as? NoteFieldsLoad.Ok)?.fields.orEmpty(),
                        fieldsReadable = loadedFields is NoteFieldsLoad.Ok,
                        noteId = noteId,
                        folderId = existing.folderId,
                        isLoaded = true,
                        isNewNote = false,
                        createdAt = existing.createdAt,
                        title = existing.title,
                        description = existing.description,
                        body = existing.body,
                        isFavorite = existing.isFavorite,
                        isPinned = existing.isPinned,
                        colorIndex = existing.colorIndex
                    )
                } else {
                    EditorUiState(
                        noteId = noteId,
                        folderId = effectiveFolderId(),
                        isLoaded = true,
                        isNewNote = true
                    )
                }
            }
        }
    }

    fun onTitleChange(value: String) =
        updateEditor { if (it.title == value) it else it.copy(title = value, isDirty = true) }

    fun onDescriptionChange(value: String) =
        updateEditor { if (it.description == value) it else it.copy(description = value, isDirty = true) }

    fun onBodyChange(value: String) =
        updateEditor { if (it.body == value) it else it.copy(body = value, isDirty = true) }

    fun onFieldsChange(fields: List<CustomField>) =
        updateEditor { if (!it.fieldsReadable || it.fields == fields) it else it.copy(fields = fields, isDirty = true) }

    fun onColorChange(index: Int) =
        updateEditor { if (it.colorIndex == index) it else it.copy(colorIndex = index, isDirty = true) }

    fun onFavoriteToggle() =
        updateEditor { it.copy(isFavorite = !it.isFavorite, isDirty = true) }

    fun onPinToggle() =
        updateEditor { it.copy(isPinned = !it.isPinned, isDirty = true) }

    /** Saves the open note if it changed. Used when the app goes to the background. */
    fun saveDraft() {
        viewModelScope.launch { editorLock.withLock { persistLocked() } }
    }

    /** Saves the open note if it changed, then closes the editor. */
    fun closeEditor() {
        viewModelScope.launch {
            editorLock.withLock {
                persistLocked()
                _editor.value = EditorUiState()
            }
        }
    }

    /** Deletes the open note (if it was ever saved) and closes the editor. */
    fun deleteCurrent() {
        viewModelScope.launch {
            editorLock.withLock {
                _editor.value.noteId?.let { repository.deleteNote(it) }
                _editor.value = EditorUiState()
            }
        }
    }

    private fun updateEditor(transform: (EditorUiState) -> EditorUiState) {
        _editor.update { if (it.noteId == null || !it.isLoaded) it else transform(it) }
    }

    private suspend fun persistLocked() {
        val draft = _editor.value
        val id = draft.noteId ?: return
        if (!draft.isLoaded || !draft.isDirty) return
        if (draft.isNewNote && draft.isBlank) return // never save an empty new note

        val saved = try {
            repository.save(
                NoteEntity(
                    id = id,
                    folderId = draft.folderId,
                    title = draft.title,
                    description = draft.description,
                    body = draft.body,
                    createdAt = draft.createdAt,
                    updatedAt = 0L,
                    isFavorite = draft.isFavorite,
                    isPinned = draft.isPinned,
                    colorIndex = draft.colorIndex
                ),
                // Unreadable fields are left exactly as stored, never replaced.
                fields = if (draft.fieldsReadable) draft.fields else null
            )
        } catch (e: FieldCipherException) {
            // The key is unavailable: save the note itself and keep the stored fields as they were.
            repository.save(
                NoteEntity(
                    id = id, folderId = draft.folderId, title = draft.title, description = draft.description,
                    body = draft.body, createdAt = draft.createdAt, updatedAt = 0L, isFavorite = draft.isFavorite,
                    isPinned = draft.isPinned, colorIndex = draft.colorIndex
                ),
                fields = null
            )
        }
        _editor.update {
            if (it.noteId == id) it.copy(isNewNote = false, isDirty = false, createdAt = saved.createdAt) else it
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val database = TazDatabase.getInstance(appContext)
                    val images = NoteImageStore(appContext.filesDir)
                    return NotesViewModel(
                        NoteRepository(database.noteDao(), images, cipher = KeystoreFieldCipher()),
                        FolderRepository(database.folderDao()),
                        appContext,
                        images,
                        TagRepository(database.tagDao())
                    ) as T
                }
            }
        }
    }
}

private fun buildUiState(
    allNotes: List<NoteEntity>,
    allFolders: List<FolderEntity>,
    text: String,
    onlyFavorites: Boolean,
    folderId: String?,
    tags: Map<String, List<Tag>>
): NotesUiState {
    val folderById = allFolders.associateBy { it.id }
    val current = folderId?.let { folderById[it] }
    val needle = text.trim()
    val browsing = needle.isEmpty() && !onlyFavorites

    val notes: List<NoteEntity>
    val folders: List<FolderEntity>
    if (browsing) {
        folders = allFolders.filter { it.parentId == current?.id }
        notes = allNotes.filter { note ->
            val noteFolderId = note.folderId
            if (current == null) {
                // Top level also catches a note whose folder no longer exists.
                noteFolderId == null || noteFolderId !in folderById
            } else {
                noteFolderId == current.id
            }
        }
    } else {
        folders = if (needle.isNotEmpty() && !onlyFavorites) {
            allFolders.filter { it.name.contains(needle, ignoreCase = true) }
        } else {
            emptyList()
        }
        notes = allNotes.filter { note ->
            (!onlyFavorites || note.isFavorite) && (needle.isEmpty() || note.matches(needle, tags[note.id].orEmpty()))
        }
    }

    val path = generateSequence(current) { folder -> folder.parentId?.let { folderById[it] } }
        .toList()
        .asReversed()

    return NotesUiState(
        notes = notes,
        folders = folders,
        allFolders = allFolders,
        currentFolder = current,
        path = path,
        isBrowsing = browsing,
        isLoading = false,
        hasContent = allNotes.isNotEmpty() || allFolders.isNotEmpty(),
        favoritesOnly = onlyFavorites,
        query = needle,
        tags = tags
    )
}

/** Every word must appear in the title, description, text or a tag name of the note. */
private fun NoteEntity.matches(needle: String, tags: List<Tag>): Boolean =
    NoteSearch.matches(title, description, body + "\n" + tags.joinToString(" ") { it.name }, needle)
