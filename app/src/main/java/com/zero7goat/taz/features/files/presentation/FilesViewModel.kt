package com.zero7goat.taz.features.files.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zero7goat.taz.core.database.FolderEntity
import com.zero7goat.taz.core.database.FolderModule
import com.zero7goat.taz.core.database.FolderRepository
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.storage.VaultFileStore
import com.zero7goat.taz.features.files.data.ExternalFile
import com.zero7goat.taz.features.files.data.FileEntity
import com.zero7goat.taz.features.files.data.FileRepository
import com.zero7goat.taz.features.files.data.ImportFailure
import com.zero7goat.taz.features.files.data.ImportResult
import com.zero7goat.taz.features.files.domain.DateFilter
import com.zero7goat.taz.features.files.domain.FileCategory
import com.zero7goat.taz.features.files.domain.FileSort
import com.zero7goat.taz.features.files.domain.FilesQuery
import com.zero7goat.taz.features.files.domain.SizeFilter
import com.zero7goat.taz.features.files.domain.selectFiles
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the files screen shows. See [com.zero7goat.taz.features.files.domain.FileSelection]. */
data class FilesUiState(
    val files: List<FileEntity> = emptyList(),
    val folders: List<FolderEntity> = emptyList(),
    val allFolders: List<FolderEntity> = emptyList(),
    val currentFolder: FolderEntity? = null,
    val path: List<FolderEntity> = emptyList(),
    val isBrowsing: Boolean = true,
    val isLoading: Boolean = true,
    val query: FilesQuery = FilesQuery()
)

data class ImportProgress(val current: Int, val total: Int)

class FilesViewModel(
    private val repository: FileRepository,
    private val folderRepository: FolderRepository
) : ViewModel() {

    private val query = MutableStateFlow(FilesQuery())

    val uiState: StateFlow<FilesUiState> = combine(
        repository.observeFiles(),
        folderRepository.observeFolders(FolderModule.FILES),
        query
    ) { files, folders, currentQuery ->
        val selection = selectFiles(files, folders, currentQuery, System.currentTimeMillis())
        FilesUiState(
            files = selection.files,
            folders = selection.folders,
            allFolders = folders,
            currentFolder = selection.currentFolder,
            path = selection.path,
            isBrowsing = selection.isBrowsing,
            isLoading = false,
            query = currentQuery
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-off messages for the screen to show (import results). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // Remove what an interrupted import or an earlier session left behind. Best effort.
        viewModelScope.launch {
            try {
                repository.cleanupLeftovers()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing to do: leftovers are harmless and will be retried next time.
            }
        }
    }

    // ---------- search, filters, folders ----------

    fun onQueryChange(text: String) = query.update { it.copy(text = text) }

    fun setFavoritesOnly(value: Boolean) = query.update { it.copy(favoritesOnly = value) }

    fun setSort(sort: FileSort) = query.update { it.copy(sort = sort) }

    fun setTypeFilter(category: FileCategory?) = query.update { it.copy(typeFilter = category) }

    fun setSizeFilter(filter: SizeFilter) = query.update { it.copy(sizeFilter = filter) }

    fun setDateFilter(filter: DateFilter) = query.update { it.copy(dateFilter = filter) }

    fun resetFilters() = query.update {
        it.copy(sort = FileSort.NAME_ASC, typeFilter = null, sizeFilter = SizeFilter.ANY, dateFilter = DateFilter.ANY)
    }

    /** Shows the contents of [folderId], or the top level when null. */
    fun openFolder(folderId: String?) = query.update { it.copy(folderId = folderId) }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepository.createFolder(FolderModule.FILES, trimmed, parentId = effectiveFolderId())
        }
    }

    fun renameFolder(folder: FolderEntity, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == folder.name) return
        viewModelScope.launch { folderRepository.updateFolder(folder.copy(name = trimmed)) }
    }

    /** Deletes the folder and its subfolders. Files inside are kept and move to the top level. */
    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch { folderRepository.deleteFolder(folder) }
    }

    private suspend fun effectiveFolderId(): String? {
        val id = query.value.folderId ?: return null
        return if (folderRepository.getFolders(FolderModule.FILES).any { it.id == id }) id else null
    }

    // ---------- import ----------

    /** Copies the chosen documents into private storage, one after another, in the folder being browsed. */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_importProgress.value != null) {
            _messages.tryEmit("An import is already running")
            return
        }
        _importProgress.value = ImportProgress(0, uris.size)
        viewModelScope.launch {
            var imported = 0
            var failed = 0
            var lastFailure: ImportFailure? = null
            try {
                val folderId = effectiveFolderId()
                uris.forEachIndexed { index, uri ->
                    _importProgress.value = ImportProgress(index + 1, uris.size)
                    when (val result = repository.importUri(uri, folderId)) {
                        is ImportResult.Imported -> imported++
                        is ImportResult.Failed -> {
                            failed++
                            lastFailure = result.reason
                        }
                    }
                }
            } finally {
                _importProgress.value = null
            }
            _messages.tryEmit(importSummary(imported, failed, lastFailure))
        }
    }

    private fun importSummary(imported: Int, failed: Int, lastFailure: ImportFailure?): String {
        val reason = when (lastFailure) {
            ImportFailure.NOT_ENOUGH_STORAGE -> "Not enough free storage"
            ImportFailure.CANNOT_READ -> "The file could not be read"
            ImportFailure.STORAGE_ERROR -> "A storage error occurred"
            null -> ""
        }
        val files = { n: Int -> if (n == 1) "1 file" else "$n files" }
        return when {
            failed == 0 -> "Imported ${files(imported)}"
            imported == 0 -> "Import failed. $reason."
            else -> "Imported ${files(imported)}, ${files(failed)} failed. $reason."
        }
    }

    // ---------- single files ----------

    fun observeFile(id: String): Flow<FileEntity?> = repository.observeFile(id)

    /** The stored copy if it exists and its recorded path is safe, otherwise null. */
    fun resolveBlob(file: FileEntity): File? = repository.resolveBlob(file)

    suspend fun prepareExternal(file: FileEntity): ExternalFile = repository.prepareExternal(file)

    fun toggleFavorite(file: FileEntity) {
        viewModelScope.launch { repository.setFavorite(file.id, !file.isFavorite) }
    }

    fun updateDetails(file: FileEntity, displayName: String, keywords: String, description: String) {
        viewModelScope.launch { repository.updateDetails(file, displayName, keywords, description) }
    }

    fun moveFile(file: FileEntity, folderId: String?) {
        viewModelScope.launch { repository.moveFile(file, folderId) }
    }

    fun deleteFile(file: FileEntity) {
        viewModelScope.launch { repository.delete(file) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val database = TazDatabase.getInstance(appContext)
                    val repository = FileRepository(
                        dao = database.fileDao(),
                        store = VaultFileStore(appContext.filesDir),
                        appContext = appContext
                    )
                    return FilesViewModel(repository, FolderRepository(database.folderDao())) as T
                }
            }
        }
    }
}
