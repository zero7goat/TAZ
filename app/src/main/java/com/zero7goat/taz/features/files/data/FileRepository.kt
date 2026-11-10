package com.zero7goat.taz.features.files.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.zero7goat.taz.core.database.TrashRow
import com.zero7goat.taz.core.storage.ShareStaging
import com.zero7goat.taz.core.storage.VaultFileStore
import com.zero7goat.taz.core.storage.extensionOf
import com.zero7goat.taz.core.storage.sanitizeFileName
import com.zero7goat.taz.core.storage.uniqueName
import com.zero7goat.taz.features.files.domain.normalizeKeywords
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ImportFailure { CANNOT_READ, NOT_ENOUGH_STORAGE, STORAGE_ERROR }

sealed interface ImportResult {
    data class Imported(val file: FileEntity) : ImportResult
    data class Failed(val reason: ImportFailure) : ImportResult
}

/** A stored file prepared for another app, or why it could not be. */
sealed interface ExternalFile {
    data class Ready(val uri: Uri, val mimeType: String, val displayName: String) : ExternalFile
    data object Missing : ExternalFile
    data object Failed : ExternalFile
}

/**
 * Imports files into private storage and keeps their metadata in Room.
 *
 * All copying and file work runs on [Dispatchers.IO]. Files are streamed in small chunks, so
 * size is limited only by free storage. The user's original file is only ever read.
 */
class FileRepository(
    private val dao: FileDao,
    private val store: VaultFileStore,
    private val appContext: Context,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    // Serializes "move into the vault + insert row", deletion and orphan cleanup, so cleanup can
    // never remove a file that has just been stored but not yet recorded.
    private val storageLock = Mutex()

    fun observeFiles(): Flow<List<FileEntity>> = dao.observeAll()

    fun observeFile(id: String): Flow<FileEntity?> = dao.observeById(id)

    /**
     * Copies the document at [uri] into the vault and records it in [folderId] (null = top level).
     * Never modifies or deletes the source. On any failure the partial copy is removed.
     */
    suspend fun importUri(uri: Uri, folderId: String?): ImportResult = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val source = readSourceInfo(resolver, uri)
        val originalName = sanitizeFileName(source.name ?: uri.lastPathSegment)
        val mimeType = resolveMimeType(source.mimeType, originalName)

        val reportedSize = source.size
        if (reportedSize != null && reportedSize > 0 &&
            reportedSize + STORAGE_RESERVE_BYTES > store.usableSpace()
        ) {
            return@withContext ImportResult.Failed(ImportFailure.NOT_ENOUGH_STORAGE)
        }

        val temp = try {
            store.newTempFile()
        } catch (e: IOException) {
            return@withContext ImportResult.Failed(ImportFailure.STORAGE_ERROR)
        }

        try {
            val input = resolver.openInputStream(uri)
                ?: return@withContext ImportResult.Failed(ImportFailure.CANNOT_READ)
            val copied = input.use { copyStream(it, temp) }
            if (copied == 0L && (reportedSize ?: 0L) > 0L) {
                return@withContext ImportResult.Failed(ImportFailure.CANNOT_READ)
            }

            val id = UUID.randomUUID().toString()
            storageLock.withLock {
                val relativePath = store.commit(temp, id)
                try {
                    val taken = dao.namesInFolder(folderId).map { it.displayName }
                    val timestamp = now()
                    val entity = FileEntity(
                        id = id,
                        folderId = folderId,
                        originalName = originalName,
                        displayName = uniqueName(originalName, taken),
                        mimeType = mimeType,
                        sizeBytes = copied,
                        storedPath = relativePath,
                        keywords = "",
                        description = "",
                        isFavorite = false,
                        createdAt = timestamp,
                        updatedAt = timestamp
                    )
                    dao.insert(entity)
                    ImportResult.Imported(entity)
                } catch (e: Exception) {
                    // The file was moved in but could not be recorded: do not leave it behind.
                    store.delete(relativePath)
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ImportResult.Failed(ImportFailure.CANNOT_READ)
        } catch (e: FileNotFoundException) {
            ImportResult.Failed(ImportFailure.CANNOT_READ)
        } catch (e: IOException) {
            val full = store.usableSpace() < STORAGE_RESERVE_BYTES
            ImportResult.Failed(if (full) ImportFailure.NOT_ENOUGH_STORAGE else ImportFailure.STORAGE_ERROR)
        } catch (e: Exception) {
            ImportResult.Failed(ImportFailure.STORAGE_ERROR)
        } finally {
            store.discardTemp(temp) // no-op if the file was moved into the vault
        }
    }

    /** Moves the file to the trash. Its stored copy is kept until the file is purged. */
    suspend fun delete(file: FileEntity) = dao.moveToTrash(file.id, now())

    /**
     * Takes a file out of the trash. If another file in the same folder now has its name, it gets
     * a " (2)" style suffix, so a restore never overwrites or duplicates a name.
     */
    suspend fun restore(id: String) {
        val file = dao.getById(id) ?: return
        val taken = dao.namesInFolder(file.folderId).filter { it.id != id }.map { it.displayName }
        dao.restoreFromTrash(id, uniqueName(file.displayName, taken))
    }

    fun observeTrash(): Flow<List<TrashRow>> = dao.observeTrash()

    suspend fun trashedIds(): List<String> = dao.trashedIds()

    suspend fun trashedBefore(cutoff: Long): List<String> = dao.trashedBefore(cutoff)

    /** Permanently deletes the record and the stored copy. The user's original file is not involved. */
    suspend fun purge(id: String) {
        withContext(Dispatchers.IO) {
            storageLock.withLock {
                val file = dao.getById(id) ?: return@withContext
                dao.deleteById(file.id)
                store.delete(file.storedPath) // an undeletable leftover is cleaned up later
            }
        }
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) = dao.setFavorite(id, isFavorite)

    /**
     * Changes the display name, keywords and description. The name is sanitized and, if another
     * file in the same folder already has it, gets a " (2)" style suffix. A blank name is ignored.
     */
    suspend fun updateDetails(
        file: FileEntity,
        displayName: String,
        keywordsInput: String,
        description: String
    ) {
        val taken = dao.namesInFolder(file.folderId).filter { it.id != file.id }.map { it.displayName }
        val requested = sanitizeFileName(displayName.ifBlank { file.displayName })
        dao.updateMetadata(
            id = file.id,
            displayName = uniqueName(requested, taken),
            keywords = normalizeKeywords(keywordsInput),
            description = description.trim(),
            updatedAt = now()
        )
    }

    /** Moves a file into [folderId] (null = top level), renaming it if the name is taken there. */
    suspend fun moveFile(file: FileEntity, folderId: String?) {
        val taken = dao.namesInFolder(folderId).filter { it.id != file.id }.map { it.displayName }
        dao.move(file.id, folderId, uniqueName(file.displayName, taken))
    }

    /** The stored copy of [file] if its recorded path is safe and the file exists, otherwise null. */
    fun resolveBlob(file: FileEntity): File? = store.resolve(file.storedPath)?.takeIf { it.isFile }

    /** Prepares [file] for sharing or opening in another app. Runs on a background thread. */
    suspend fun prepareExternal(file: FileEntity): ExternalFile = withContext(Dispatchers.IO) {
        val blob = resolveBlob(file) ?: return@withContext ExternalFile.Missing
        try {
            val uri = ShareStaging.stage(appContext, blob, file.displayName)
            ExternalFile.Ready(uri, file.mimeType.ifBlank { GENERIC_MIME }, file.displayName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExternalFile.Failed
        }
    }

    /**
     * Removes what interrupted imports and earlier sessions left behind: unfinished temporary
     * copies, old staged share copies, and stored copies with no record. Orphans are only removed
     * if they are more than a day old, and never when the database has no files at all, so a
     * database problem cannot wipe the user's files.
     */
    suspend fun cleanupLeftovers() {
        withContext(Dispatchers.IO) {
            storageLock.withLock {
                store.cleanTemp()
                val known = dao.allStoredPaths().toSet()
                if (known.isNotEmpty()) store.deleteBlobsNotIn(known, olderThanMs = ORPHAN_MIN_AGE_MS)
            }
            ShareStaging.clear(appContext)
        }
    }

    // ---------- helpers ----------

    private class SourceInfo(val name: String?, val size: Long?, val mimeType: String?)

    private fun readSourceInfo(resolver: ContentResolver, uri: Uri): SourceInfo {
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
        } catch (e: Exception) {
            // Some providers refuse the query; fall back to what the URI itself tells us.
        }
        val mime = try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        return SourceInfo(name, size, mime)
    }

    private fun resolveMimeType(providerType: String?, name: String): String {
        val usable = providerType?.takeIf { it.isNotBlank() && it != GENERIC_MIME }
        if (usable != null) return usable
        val fromExtension = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(name))
        return fromExtension ?: providerType?.takeIf { it.isNotBlank() } ?: GENERIC_MIME
    }

    /** Streams [input] into [target] in small chunks. Stops promptly if the coroutine is cancelled. */
    private suspend fun copyStream(input: InputStream, target: File): Long {
        var total = 0L
        java.io.FileOutputStream(target).use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
                coroutineContext.ensureActive()
            }
            output.flush()
            output.fd.sync()
        }
        return total
    }

    private companion object {
        const val GENERIC_MIME = "application/octet-stream"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val STORAGE_RESERVE_BYTES = 20L * 1024 * 1024
        const val ORPHAN_MIN_AGE_MS = 24L * 60 * 60 * 1000
    }
}
