package com.zero7goat.tung.features.passwords.presentation

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zero7goat.tung.core.database.FolderEntity
import com.zero7goat.tung.core.database.FolderModule
import com.zero7goat.tung.core.database.FolderRepository
import com.zero7goat.tung.core.database.TungDatabase
import com.zero7goat.tung.core.security.FieldCipherException
import com.zero7goat.tung.core.security.KeystoreFieldCipher
import com.zero7goat.tung.core.ui.replaceAllText
import com.zero7goat.tung.features.passwords.data.CredentialListRow
import com.zero7goat.tung.features.passwords.data.CredentialRepository
import com.zero7goat.tung.features.passwords.data.DetailResult
import com.zero7goat.tung.features.passwords.domain.CredentialDetail
import com.zero7goat.tung.features.passwords.domain.CredentialInput
import com.zero7goat.tung.features.passwords.domain.CredentialListItem
import com.zero7goat.tung.features.passwords.domain.matchesCredentialSearch
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
    val favoritesOnly: Boolean = false
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

    val canSave: Boolean
        get() = serviceName.text.isNotBlank() && password.text.isNotEmpty()

    fun resetTo(detail: CredentialDetail?) {
        serviceName.replaceAllText(detail?.serviceName.orEmpty())
        username.replaceAllText(detail?.username.orEmpty())
        password.replaceAllText(detail?.password.orEmpty())
        websiteUrl.replaceAllText(detail?.websiteUrl.orEmpty())
        notes.replaceAllText(detail?.notes.orEmpty())
    }

    fun clear() = resetTo(null)

    fun hasChanges(saved: CredentialDetail?): Boolean =
        if (saved == null) {
            listOf(serviceName, username, password, websiteUrl, notes).any { it.text.isNotEmpty() }
        } else {
            serviceName.text.toString() != saved.serviceName ||
                username.text.toString() != saved.username ||
                password.text.toString() != saved.password ||
                websiteUrl.text.toString() != saved.websiteUrl ||
                notes.text.toString() != saved.notes
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
        createdAt = createdAt
    )
}

class PasswordsViewModel(
    private val repository: CredentialRepository,
    private val folderRepository: FolderRepository
) : ViewModel() {

    // ---------- list and folders ----------

    private val query = MutableStateFlow("")
    private val favoritesOnly = MutableStateFlow(false)
    private val currentFolderId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PasswordsUiState> = combine(
        repository.observeListRows(),
        folderRepository.observeFolders(FolderModule.PASSWORDS),
        query,
        favoritesOnly,
        currentFolderId
    ) { rows, folders, text, onlyFavorites, folderId ->
        buildUiState(rows, folders, text, onlyFavorites, folderId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PasswordsUiState())

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

    private suspend fun effectiveFolderId(): String? {
        val id = currentFolderId.value ?: return null
        return if (folderRepository.getFolders(FolderModule.PASSWORDS).any { it.id == id }) id else null
    }

    private suspend fun buildUiState(
        allRows: List<CredentialListRow>,
        allFolders: List<FolderEntity>,
        text: String,
        onlyFavorites: Boolean,
        folderId: String?
    ): PasswordsUiState {
        val folderById = allFolders.associateBy { it.id }
        val current = folderId?.let { folderById[it] }
        val needle = text.trim()
        val browsing = needle.isEmpty() && !onlyFavorites

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
            // Search looks only at the website address and domain (not folder or service names),
            // and it runs on the plain columns before anything is decrypted.
            folders = emptyList()
            rows = allRows.filter { row ->
                (!onlyFavorites || row.isFavorite) &&
                    (needle.isEmpty() ||
                        matchesCredentialSearch(row.websiteUrl, row.domain, needle))
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
                    isFavorite = row.isFavorite
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
            favoritesOnly = onlyFavorites
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
                when (val result = repository.getDetail(id)) {
                    is DetailResult.Found -> {
                        val detail = result.detail
                        draft = CredentialDraft(detail)
                        _editor.value = CredentialEditorUi(
                            credentialId = id,
                            status = EditorStatus.Ready,
                            isFavorite = detail.isFavorite,
                            folderId = detail.folderId,
                            saved = detail
                        )
                    }
                    DetailResult.Missing -> {
                        draft = CredentialDraft(null)
                        _editor.value = CredentialEditorUi(
                            credentialId = id,
                            status = EditorStatus.Ready,
                            isNew = true,
                            isEditing = true,
                            folderId = effectiveFolderId()
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
        repository.clearCache()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val database = TungDatabase.getInstance(appContext)
                    return PasswordsViewModel(
                        CredentialRepository(database.credentialDao(), KeystoreFieldCipher()),
                        FolderRepository(database.folderDao())
                    ) as T
                }
            }
        }
    }
}
