package com.zero7goat.taz.features.passwords.presentation

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.zero7goat.taz.core.database.FolderEntity
import com.zero7goat.taz.core.database.FolderModule
import com.zero7goat.taz.core.database.FolderRepository
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.security.FieldCipher
import com.zero7goat.taz.core.security.FieldCipherException
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zero7goat.taz.core.fields.CustomFields
import com.zero7goat.taz.core.tags.Tag
import com.zero7goat.taz.core.tags.TagRepository
import com.zero7goat.taz.core.tags.TagTarget
import com.zero7goat.taz.core.ui.replaceAllText
import com.zero7goat.taz.features.passwords.data.CategoryRepository
import com.zero7goat.taz.features.passwords.domain.Categories
import com.zero7goat.taz.features.passwords.domain.LoginCategory
import com.zero7goat.taz.features.passwords.domain.canSaveCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.zero7goat.taz.features.passwords.data.CredentialListRow
import com.zero7goat.taz.features.passwords.data.CredentialRepository
import com.zero7goat.taz.features.passwords.data.DetailResult
import com.zero7goat.taz.features.passwords.domain.CredentialDetail
import com.zero7goat.taz.features.passwords.domain.CredentialInput
import com.zero7goat.taz.features.passwords.domain.CredentialListItem
import com.zero7goat.taz.features.passwords.domain.matchesCredentialSearch
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the passwords screen shows. While [isBrowsing] (no search, no favorites filter),
 * [folders] and [items] are the contents of [currentFolder] (or the top level); otherwise they
 * are results across all folders. Items never contain passwords or notes.
 */
data class PasswordsUiState(
    val items: List<CredentialListItem> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val allFolders: List<FolderEntity> = emptyList(),
    val currentFolder: FolderEntity? = null,
    val path: List<FolderEntity> = emptyList(),
    val isBrowsing: Boolean = true,
    val isLoading: Boolean = true,
    val favoritesOnly: Boolean = false,
    /** Tags of each login, by id. */
    val tags: Map<String, List<Tag>> = emptyMap(),
    val categories: List<LoginCategory> = Categories.builtIn,
    /** The category being filtered by, or null for all. */
    val categoryFilter: String? = null
)

enum class EditorStatus { Closed, Loading, Ready, Unreadable }

/** State of the open login screen. Text being typed lives in [CredentialDraft], not here. */
data class CredentialEditorUi(
    val credentialId: String? = null,
    val status: EditorStatus = EditorStatus.Closed,
    val isNew: Boolean = false,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val isFavorite: Boolean = false,
    val folderId: String? = null,
    val saved: CredentialDetail? = null
)

/**
 * The text fields of the login being edited. They are plain in-memory state held by the
 * ViewModel, so edits survive rotation but are never written to saved instance state.
 */
class CredentialDraft(initial: CredentialDetail?) {
    val serviceName = TextFieldState(initial?.serviceName.orEmpty())
    val username = TextFieldState(initial?.username.orEmpty())
    val password = TextFieldState(initial?.password.orEmpty())
    val websiteUrl = TextFieldState(initial?.websiteUrl.orEmpty())
    val notes = TextFieldState(initial?.notes.orEmpty())

    /** The category key (see Categories). */
    var category by mutableStateOf(initial?.category ?: Categories.LOGIN.key)

    /** Custom fields, in display order. Values are plain text in memory only. */
    var fields by mutableStateOf(initial?.fields.orEmpty())

    val canSave: Boolean
        get() = canSaveCredential(serviceName.text.toString(), password.text.toString(), category) &&
            fields.all { CustomFields.isValidValue(it.type, it.value) }

    fun resetTo(detail: CredentialDetail?) {
        serviceName.replaceAllText(detail?.serviceName.orEmpty())
        username.replaceAllText(detail?.username.orEmpty())
        password.replaceAllText(detail?.password.orEmpty())
        websiteUrl.replaceAllText(detail?.websiteUrl.orEmpty())
        notes.replaceAllText(detail?.notes.orEmpty())
        category = detail?.category ?: Categories.LOGIN.key
        fields = detail?.fields.orEmpty()
    }

    fun clear() = resetTo(null)

    /**
     * Switches to [newCategory]. Its suggested fields are added unless a field with the same name
     * already exists; nothing typed is removed.
     */
    fun applyCategory(newCategory: LoginCategory) {
        category = newCategory.key
        val existing = fields.mapTo(HashSet()) { it.label.trim().lowercase() }
        fields = fields + newCategory.newFields().filter { it.label.trim().lowercase() !in existing }
    }

    fun hasChanges(saved: CredentialDetail?): Boolean =
        if (saved == null) {
            listOf(serviceName, username, password, websiteUrl, notes).any { it.text.isNotEmpty() } ||
                fields.any { it.value.isNotEmpty() && it.value != CustomFields.FALSE }
        } else {
            serviceName.text.toString() != saved.serviceName ||
                username.text.toString() != saved.username ||
                password.text.toString() != saved.password ||
                websiteUrl.text.toString() != saved.websiteUrl ||
                notes.text.toString() != saved.notes ||
                category != saved.category ||
                CustomFields.normalize(fields) != saved.fields
        }

    fun toInput(id: String, folderId: String?, isFavorite: Boolean, createdAt: Long) = CredentialInput(
        id = id,
        folderId = folderId,
        serviceName = serviceName.text.toString(),
        username = username.text.toString(),
        password = password.text.toString(),
        websiteUrl = websiteUrl.text.toString(),
        notes = notes.text.toString(),
        isFavorite = isFavorite,
        createdAt = createdAt,
        category = category,
        fields = fields
    )
}

class PasswordsViewModel(
    private val repository: CredentialRepository,
    private val folderRepository: FolderRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val draftCipher: FieldCipher? = null,
    categoryRepository: CategoryRepository? = null,
    tagRepository: TagRepository? = null
) : ViewModel() {

    /** Built-in categories followed by the user's own. */
    val categories: StateFlow<List<LoginCategory>> =
        (categoryRepository?.observeAll() ?: flowOf(Categories.builtIn))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Categories.builtIn)

    private val tagsByLogin: Flow<Map<String, List<Tag>>> =
        tagRepository?.observeTagsByItem(TagTarget.LOGIN) ?: flowOf(emptyMap())

    // ---------- keeping unsaved typing ----------

    /**
     * A draft written before Android stopped the app process, waiting to be put back when the same
     * login is opened again. Only ever held encrypted (see [snapshotDraft]).
     */
    private var pendingRestore: Bundle? = savedState.get<Bundle>(DRAFT_KEY)?.takeIf { it.containsKey(KEY_BLOB) }

    init {
        // Called by Android when the activity saves its state (going to the background, rotation,
        // before the process may be stopped). Unsaved typing is encrypted with the device Keystore
        // key first, so no login text ever lands in saved state as plain text.
        savedState.setSavedStateProvider(DRAFT_KEY) { snapshotDraft() }
    }

    // ---------- list and folders ----------

    private val query = MutableStateFlow("")
    private val favoritesOnly = MutableStateFlow(false)
    private val currentFolderId = MutableStateFlow<String?>(null)
    private val categoryFilter = MutableStateFlow<String?>(null)

    private data class ListFilters(val text: String, val favoritesOnly: Boolean, val folderId: String?, val category: String?)

    private val filters = combine(query, favoritesOnly, currentFolderId, categoryFilter) { text, favorites, folder, category ->
        ListFilters(text, favorites, folder, category)
    }

    val uiState: StateFlow<PasswordsUiState> = combine(
        repository.observeListRows(),
        folderRepository.observeFolders(FolderModule.PASSWORDS),
        filters,
        tagsByLogin,
        categories
    ) { rows, folders, filter, tags, allCategories ->
        buildUiState(rows, folders, filter, tags, allCategories)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PasswordsUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Shows only entries of [key], across all folders. Null shows every category. */
    fun setCategoryFilter(key: String?) {
        categoryFilter.value = key
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
            folderRepository.createFolder(FolderModule.PASSWORDS, trimmed, parentId = effectiveFolderId())
        }
    }

    fun renameFolder(folder: FolderEntity, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == folder.name) return
        viewModelScope.launch { folderRepository.updateFolder(folder.copy(name = trimmed)) }
    }

    /** Deletes the folder and its subfolders. Logins inside are kept and move to the top level. */
    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch { folderRepository.deleteFolder(folder) }
    }

    fun moveCredential(id: String, folderId: String?) {
        viewModelScope.launch { repository.setFolder(id, folderId) }
    }

    fun toggleFavorite(item: CredentialListItem) {
        viewModelScope.launch { repository.setFavorite(item.id, !item.isFavorite) }
    }

    fun deleteCredential(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    // ---------- several logins at once ----------

    /** Moves the logins to the trash (restorable for 30 days), never erases them. */
    fun deleteCredentials(ids: Collection<String>) {
        val targets = ids.toList()
        viewModelScope.launch { targets.forEach { repository.delete(it) } }
    }

    fun moveCredentials(ids: Collection<String>, folderId: String?) {
        val targets = ids.toList()
        viewModelScope.launch { targets.forEach { repository.setFolder(it, folderId) } }
    }

    fun setFavorite(ids: Collection<String>, isFavorite: Boolean) {
        val targets = ids.toList()
        viewModelScope.launch { targets.forEach { repository.setFavorite(it, isFavorite) } }
    }

    /** Creates a folder inside [parentId] right away and returns its id (used by the folder picker). */
    suspend fun createFolderNow(name: String, parentId: String?): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return folderRepository.createFolder(FolderModule.PASSWORDS, trimmed, parentId).id
    }

    private suspend fun effectiveFolderId(): String? {
        val id = currentFolderId.value ?: return null
        return if (folderRepository.getFolders(FolderModule.PASSWORDS).any { it.id == id }) id else null
    }

    private suspend fun buildUiState(
        allRows: List<CredentialListRow>,
        allFolders: List<FolderEntity>,
        filter: ListFilters,
        tags: Map<String, List<Tag>>,
        allCategories: List<LoginCategory>
    ): PasswordsUiState {
        val onlyFavorites = filter.favoritesOnly
        val folderById = allFolders.associateBy { it.id }
        val current = filter.folderId?.let { folderById[it] }
        val needle = filter.text.trim()
        val category = filter.category
        val browsing = needle.isEmpty() && !onlyFavorites && category == null

        val rows: List<CredentialListRow>
        val folders: List<FolderEntity>
        if (browsing) {
            folders = allFolders.filter { it.parentId == current?.id }
            rows = allRows.filter { row ->
                val rowFolderId = row.folderId
                if (current == null) {
                    // Top level also catches an entry whose folder no longer exists.
                    rowFolderId == null || rowFolderId !in folderById
                } else {
                    rowFolderId == current.id
                }
            }
        } else {
            // This quick search looks at the website address, domain and tag names, on the plain
            // columns before anything is decrypted. Search everything (the search screen) goes further.
            folders = emptyList()
            rows = allRows.filter { row ->
                (!onlyFavorites || row.isFavorite) &&
                    (category == null || row.category == category) &&
                    (needle.isEmpty() ||
                        matchesCredentialSearch(row.websiteUrl, row.domain, needle) ||
                        tags[row.id].orEmpty().any { it.name.contains(needle, ignoreCase = true) })
            }
        }

        val path = generateSequence(current) { folder -> folder.parentId?.let { folderById[it] } }
            .toList()
            .asReversed()

        // Only the usernames of the rows that are actually shown get decrypted.
        val items = withContext(Dispatchers.Default) {
            rows.map { row ->
                CredentialListItem(
                    id = row.id,
                    folderId = row.folderId,
                    serviceName = row.serviceName,
                    websiteUrl = row.websiteUrl,
                    domain = row.domain,
                    username = repository.usernameFor(row),
                    isFavorite = row.isFavorite,
                    category = row.category
                )
            }
        }

        return PasswordsUiState(
            items = items,
            folders = folders,
            allFolders = allFolders,
            currentFolder = current,
            path = path,
            isBrowsing = browsing,
            isLoading = false,
            favoritesOnly = onlyFavorites,
            tags = tags,
            categories = allCategories,
            categoryFilter = category
        )
    }

    // ---------- login screen ----------

    private val _editor = MutableStateFlow(CredentialEditorUi())
    val editor: StateFlow<CredentialEditorUi> = _editor.asStateFlow()

    /** The text fields of the open login. Non-null while the editor status is Ready. */
    var draft: CredentialDraft? = null
        private set

    // Serializes open, save and close so quick actions cannot interleave.
    private val editorLock = Mutex()

    /** Opens [id]. An id with no stored login starts a new entry in the folder being browsed. */
    fun openEditor(id: String) {
        viewModelScope.launch {
            editorLock.withLock {
                val current = _editor.value
                if (current.credentialId == id && current.status != EditorStatus.Closed) return@withLock

                _editor.value = CredentialEditorUi(credentialId = id, status = EditorStatus.Loading)
                val restored = takePendingRestore(id)
                when (val result = repository.getDetail(id)) {
                    is DetailResult.Found -> {
                        val detail = result.detail
                        draft = CredentialDraft(detail).also { restored?.applyTo(it) }
                        _editor.value = CredentialEditorUi(
                            credentialId = id,
                            status = EditorStatus.Ready,
                            isEditing = restored != null,
                            isFavorite = detail.isFavorite,
                            folderId = detail.folderId,
                            saved = detail
                        )
                    }
                    DetailResult.Missing -> {
                        draft = CredentialDraft(null).also { restored?.applyTo(it) }
                        _editor.value = CredentialEditorUi(
                            credentialId = id,
                            status = EditorStatus.Ready,
                            isNew = true,
                            isEditing = true,
                            isFavorite = restored?.isFavorite ?: false,
                            folderId = if (restored != null) restored.folderId else effectiveFolderId()
                        )
                    }
                    DetailResult.Unreadable -> {
                        draft = null
                        _editor.value = CredentialEditorUi(credentialId = id, status = EditorStatus.Unreadable)
                    }
                }
            }
        }
    }

    fun startEditing() {
        _editor.update { if (it.status == EditorStatus.Ready) it.copy(isEditing = true) else it }
    }

    /** Throws away unsaved changes of an existing entry and returns to the read-only view. */
    fun cancelEditing() {
        _editor.update {
            if (it.status == EditorStatus.Ready && !it.isNew) {
                draft?.resetTo(it.saved)
                it.copy(isEditing = false, saveFailed = false)
            } else {
                it
            }
        }
    }

    fun save() {
        val state = _editor.value
        val currentDraft = draft ?: return
        val id = state.credentialId ?: return
        if (state.status != EditorStatus.Ready || state.isSaving || !currentDraft.canSave) return

        val input = currentDraft.toInput(
            id = id,
            folderId = state.folderId,
            isFavorite = state.isFavorite,
            createdAt = state.saved?.createdAt ?: 0L
        )
        _editor.update { it.copy(isSaving = true, saveFailed = false) }
        viewModelScope.launch {
            editorLock.withLock {
                try {
                    val saved = repository.save(input)
                    currentDraft.resetTo(saved)
                    _editor.update {
                        if (it.credentialId == id) {
                            it.copy(saved = saved, isNew = false, isEditing = false, isSaving = false)
                        } else {
                            it
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Deliberately no logging and no message: the failure may involve credentials.
                    _editor.update { it.copy(isSaving = false, saveFailed = true) }
                }
            }
        }
    }

    fun acknowledgeSaveError() {
        _editor.update { it.copy(saveFailed = false) }
    }

    fun toggleEditorFavorite() {
        val state = _editor.value
        val id = state.credentialId ?: return
        val newValue = !state.isFavorite
        if (state.isNew) {
            _editor.update { it.copy(isFavorite = newValue) }
            return
        }
        viewModelScope.launch {
            editorLock.withLock {
                repository.setFavorite(id, newValue)
                _editor.update {
                    it.copy(isFavorite = newValue, saved = it.saved?.copy(isFavorite = newValue))
                }
            }
        }
    }

    /** Deletes the open login (if it was ever saved) and closes the screen state. */
    fun deleteCurrent() {
        viewModelScope.launch {
            editorLock.withLock {
                _editor.value.credentialId?.let { repository.delete(it) }
                resetEditorLocked()
            }
        }
    }

    /**
     * The app has just locked. A login that is only being viewed is wiped from memory (it is read
     * again from the encrypted database after unlocking). A login with unsaved typing is kept as
     * it is: the lock screen covers it. Wiping it here is what made users lose everything they had
     * typed after briefly switching to another app.
     */
    fun onAppLocked() {
        viewModelScope.launch {
            editorLock.withLock {
                if (!hasUnsavedTyping()) resetEditorLocked()
            }
        }
    }

    /** True while a new or edited login has text that has not been saved yet. */
    fun hasUnsavedTyping(): Boolean {
        val state = _editor.value
        val current = draft ?: return false
        return state.status == EditorStatus.Ready && state.isEditing && current.hasChanges(state.saved)
    }

    /** Closes the login screen and wipes the in-memory text fields. */
    fun closeEditor() {
        viewModelScope.launch { editorLock.withLock { resetEditorLocked() } }
    }

    private fun resetEditorLocked() {
        draft?.clear()
        draft = null
        _editor.value = CredentialEditorUi()
    }

    override fun onCleared() {
        draft?.clear()
        draft = null
        pendingRestore = null
        repository.clearCache()
    }

    /** The unsaved draft, encrypted, for saved state. Empty when there is nothing worth keeping. */
    private fun snapshotDraft(): Bundle {
        val cipher = draftCipher ?: return Bundle()
        val state = _editor.value
        val current = draft
        val id = state.credentialId
        if (id == null || current == null || !hasUnsavedTyping()) {
            // A draft restored from an earlier process but not opened yet is kept until it is used.
            return pendingRestore ?: Bundle()
        }
        return try {
            val fields = DraftFields(
                serviceName = current.serviceName.text.toString(),
                username = current.username.text.toString(),
                password = current.password.text.toString(),
                websiteUrl = current.websiteUrl.text.toString(),
                notes = current.notes.text.toString(),
                category = current.category,
                fields = current.fields
            )
            Bundle().apply {
                putString(KEY_ID, id)
                putBoolean(KEY_FAVORITE, state.isFavorite)
                putString(KEY_FOLDER, state.folderId)
                putByteArray(KEY_BLOB, DraftSnapshot.encode(fields, id, cipher))
            }
        } catch (e: Exception) {
            Bundle() // Never fall back to plain text.
        }
    }

    private class RestoredDraft(
        val fields: DraftFields,
        val isFavorite: Boolean,
        val folderId: String?
    ) {
        fun applyTo(target: CredentialDraft) {
            target.serviceName.replaceAllText(fields.serviceName)
            target.username.replaceAllText(fields.username)
            target.password.replaceAllText(fields.password)
            target.websiteUrl.replaceAllText(fields.websiteUrl)
            target.notes.replaceAllText(fields.notes)
            target.category = fields.category
            target.fields = fields.fields
        }
    }

    /** Decrypts and removes the pending draft if it belongs to [id]. */
    private fun takePendingRestore(id: String): RestoredDraft? {
        val bundle = pendingRestore ?: return null
        if (bundle.getString(KEY_ID) != id) return null
        pendingRestore = null
        val cipher = draftCipher ?: return null
        val blob = bundle.getByteArray(KEY_BLOB) ?: return null
        // Unreadable (for example the key changed): start empty rather than fail.
        val fields = DraftSnapshot.decode(blob, id, cipher) ?: return null
        return RestoredDraft(fields, bundle.getBoolean(KEY_FAVORITE), bundle.getString(KEY_FOLDER))
    }

    companion object {
        private const val DRAFT_KEY = "credential_draft"
        private const val KEY_ID = "id"
        private const val KEY_FAVORITE = "favorite"
        private const val KEY_FOLDER = "folder"
        private const val KEY_BLOB = "blob"

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val database = TazDatabase.getInstance(appContext)
                    val cipher = KeystoreFieldCipher()
                    PasswordsViewModel(
                        CredentialRepository(database.credentialDao(), cipher),
                        FolderRepository(database.folderDao()),
                        createSavedStateHandle(),
                        cipher,
                        CategoryRepository(database.categoryDao()),
                        TagRepository(database.tagDao())
                    )
                }
            }
        }
    }
}
