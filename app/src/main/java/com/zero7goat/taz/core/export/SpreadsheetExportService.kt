package com.zero7goat.taz.core.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.storage.VaultFileStore
import com.zero7goat.taz.features.notes.data.NoteImageStore
import com.zero7goat.taz.features.notes.presentation.NoteColorNames
import java.io.BufferedOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Counts for the success message. */
data class ExportSummary(val notes: Int, val files: Int, val logins: Int)

class ExportException : Exception("Export failed")

/**
 * Exports an .xlsx index of notes, files and login metadata to a document the user created with
 * the Storage Access Framework. Runs on the device, off the main thread. Logins are read from the
 * plain columns only (service name, website, domain): no ciphertext is decrypted for the export.
 */
class SpreadsheetExportService(private val context: Context, private val database: TazDatabase) {

    suspend fun export(uri: Uri): ExportSummary = withContext(Dispatchers.IO) {
        val vault = VaultFileStore(context.applicationContext.filesDir)
        val data = database.withTransaction {
            val folders = database.folderDao().getAll()
            ExportData(
                folders = folders.map { ExportFolder(it.id, it.module, it.name, it.parentId) },
                notes = database.noteDao().getAllActive()
                    .sortedWith(compareBy({ it.title.lowercase() }, { it.createdAt }))
                    .map {
                        ExportNote(
                            title = it.title, description = it.description, body = it.body, folderId = it.folderId,
                            color = NoteColorNames.getOrElse(it.colorIndex) { "" }.takeIf { name -> name != "None" }.orEmpty(),
                            isFavorite = it.isFavorite, isPinned = it.isPinned,
                            imageCount = NoteImageStore.referencedImageNames(it.body).size,
                            createdAt = it.createdAt, updatedAt = it.updatedAt
                        )
                    },
                files = database.fileDao().getAllActive()
                    .sortedBy { it.displayName.lowercase() }
                    .map {
                        ExportFile(
                            displayName = it.displayName, originalName = it.originalName, folderId = it.folderId,
                            mimeType = it.mimeType, sizeBytes = it.sizeBytes, keywords = it.keywords,
                            description = it.description, isFavorite = it.isFavorite,
                            storedCopyPresent = vault.resolve(it.storedPath)?.isFile == true,
                            createdAt = it.createdAt, updatedAt = it.updatedAt
                        )
                    },
                logins = database.credentialDao().getAllActive()
                    .sortedBy { it.serviceName.lowercase() }
                    .map {
                        ExportLogin(
                            serviceName = it.serviceName, websiteUrl = it.websiteUrl, domain = it.domain,
                            folderId = it.folderId, isFavorite = it.isFavorite,
                            createdAt = it.createdAt, updatedAt = it.updatedAt
                        )
                    }
            )
        }

        val scope = currentCoroutineContext()
        try {
            val output = context.contentResolver.openOutputStream(uri, "w") ?: throw ExportException()
            BufferedOutputStream(output, 64 * 1024).use { buffered ->
                SpreadsheetExporter(checkCancelled = { scope.ensureActive() }).write(buffered, data)
            }
        } catch (e: Throwable) {
            // Remove the partly written document we created; never any other file.
            try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (_: Exception) {
            }
            if (e is CancellationException) throw e
            throw ExportException()
        }
        ExportSummary(notes = data.notes.size, files = data.files.size, logins = data.logins.size)
    }
}
