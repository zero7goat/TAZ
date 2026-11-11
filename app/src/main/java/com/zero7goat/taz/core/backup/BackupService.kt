package com.zero7goat.taz.core.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.zero7goat.taz.core.AppContainer
import com.zero7goat.taz.core.database.FolderEntity
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.tags.ItemTagEntity
import com.zero7goat.taz.core.tags.TagEntity
import com.zero7goat.taz.core.tags.TagNames
import com.zero7goat.taz.features.notes.data.NoteFieldsEntity
import com.zero7goat.taz.features.notes.data.NoteRepository
import com.zero7goat.taz.features.passwords.data.CategoryEntity
import com.zero7goat.taz.features.passwords.data.CredentialEntity
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.security.FieldCipher
import com.zero7goat.taz.core.security.FieldCipherException
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import com.zero7goat.taz.core.settings.AutoLockTimeout
import com.zero7goat.taz.core.settings.ClipboardClearTimeout
import com.zero7goat.taz.core.settings.FontSize
import com.zero7goat.taz.core.settings.SettingsRepository
import com.zero7goat.taz.core.settings.ThemeMode
import com.zero7goat.taz.core.storage.ShareStaging
import com.zero7goat.taz.core.storage.VaultFileStore
import com.zero7goat.taz.features.files.data.FileEntity
import com.zero7goat.taz.features.notes.data.NoteEntity
import com.zero7goat.taz.features.notes.data.NoteImageStore
import com.zero7goat.taz.features.passwords.data.CredentialRepository
import com.zero7goat.taz.features.passwords.domain.CredentialDetail
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class BackupPhase { PREPARING, ENCRYPTING, DECRYPTING, APPLYING }

/** What was restored, for the success message. */
data class RestoreSummary(
    val notes: Int,
    val logins: Int,
    val files: Int,
    val folders: Int,
    val noteImages: Int,
    val missingFiles: Int,
    val skippedLogins: Int
)

/**
 * Creates and restores encrypted backup files at locations the user picks with the Storage Access
 * Framework. Everything runs on the device, off the main thread; nothing is uploaded or scheduled.
 *
 * The passphrase is only used to derive the backup key and is wiped by the caller afterwards. It is
 * never stored or logged. Logins are decrypted with the device key only to be written into the
 * encrypted stream, and on restore are encrypted again with this installation's Keystore key; the
 * Keystore key itself is never exported, imported or replaced.
 */
class BackupService(
    private val context: Context,
    private val database: TazDatabase,
    private val cipher: FieldCipher,
    private val settings: SettingsRepository,
    private val kdfIterations: Int = BackupHeader.DEFAULT_ITERATIONS
) {
    private val filesDir = context.applicationContext.filesDir
    private val vault = VaultFileStore(filesDir)
    private val noteImages = NoteImageStore(filesDir)
    private val committer = RestoreCommitter(filesDir)
    private val credentials = CredentialRepository(database.credentialDao(), cipher)
    private val notes = NoteRepository(database.noteDao(), cipher = cipher)

    // ---------- backup ----------

    /**
     * How many saved logins cannot be decrypted on this device (for example because the Keystore
     * key was lost). Such logins can never be put into a backup; nothing is changed by checking.
     */
    suspend fun unreadableLoginCount(): Int = credentials.decryptAllForBackup().second

    /**
     * Writes an encrypted backup of everything to [uri] (a new document the user just created).
     * On failure the partly written document is deleted again. Throws [BackupException].
     *
     * Logins that cannot be decrypted cannot be included. Unless the user explicitly agreed to
     * leave them out ([allowUnreadableLogins]), the backup is aborted with
     * [BackupError.UNREADABLE_LOGINS] instead of silently producing an incomplete backup. Their
     * encrypted data on the device is never changed either way.
     */
    suspend fun createBackup(
        uri: Uri,
        passphrase: CharArray,
        allowUnreadableLogins: Boolean,
        onPhase: (BackupPhase) -> Unit
    ): BackupSummary =
        withContext(Dispatchers.IO) {
            onPhase(BackupPhase.PREPARING)
            val snapshot = database.withTransaction {
                Snapshot(
                    folders = database.folderDao().getAll(),
                    notes = database.noteDao().getAllActive(),
                    files = database.fileDao().getAllActive(),
                    tags = database.tagDao().getAll(),
                    tagLinks = database.tagDao().getAllLinks(),
                    categories = database.categoryDao().getAll()
                )
            }
            val scope = currentCoroutineContext()
            try {
                val (logins, unreadableLogins) = credentials.decryptAllForBackup()
                val (noteFields, unreadableFields) = notes.loadAllFields()
                // Note fields that cannot be decrypted count like unreadable logins: never silently dropped.
                val unreadable = unreadableLogins + unreadableFields
                if (unreadable > 0 && !allowUnreadableLogins) throw BackupException(BackupError.UNREADABLE_LOGINS)
                val contents = toContents(snapshot, logins, noteFields, currentSettings())
                val header = BackupHeader.create(kdfIterations)
                val key = BackupKeys.derive(passphrase, header)
                onPhase(BackupPhase.ENCRYPTING)

                val output = context.contentResolver.openOutputStream(uri, "w")
                    ?: throw BackupException(BackupError.CANNOT_WRITE)
                val encrypted = EncryptingOutputStream(BufferedOutputStream(output, 64 * 1024), key, header)
                try {
                    val summary = BackupArchiveWriter(checkCancelled = { scope.ensureActive() }).write(
                        out = encrypted,
                        contents = contents,
                        fileBlob = { record -> vault.resolve(vault.relativePathFor(record.id)) },
                        noteImage = { name -> noteImages.resolve(name) },
                        createdAt = System.currentTimeMillis(),
                        skippedLogins = unreadable
                    )
                    encrypted.close() // writes the authenticated last chunk only after a complete archive
                    summary
                } catch (e: Throwable) {
                    // Never seal a partial archive: close the document without the last chunk.
                    try {
                        output.close()
                    } catch (_: Exception) {
                    }
                    throw e
                }
            } catch (e: Throwable) {
                deletePartialDocument(uri)
                if (e is CancellationException || e is BackupException) throw e
                throw BackupException(BackupError.CANNOT_WRITE)
            }
        }

    private class Snapshot(
        val folders: List<FolderEntity>,
        val notes: List<NoteEntity>,
        val files: List<FileEntity>,
        val tags: List<TagEntity>,
        val tagLinks: List<ItemTagEntity>,
        val categories: List<CategoryEntity>
    )

    private fun toContents(
        snapshot: Snapshot,
        logins: List<CredentialDetail>,
        noteFields: Map<String, List<CustomField>>,
        prefs: Map<String, String>
    ): BackupContents {
        val moduleOf = snapshot.folders.associate { it.id to it.module }
        // A reference to a folder of another module (never created by the app) is dropped, so the
        // backup always passes the restore checks.
        fun folderIn(folderId: String?, module: String) = folderId?.takeIf { moduleOf[it] == module }
        return BackupContents(
            folders = snapshot.folders.map {
                FolderRecord(it.id, it.module, it.name, it.parentId?.takeIf { p -> moduleOf[p] == it.module }, it.createdAt)
            },
            notes = snapshot.notes.map {
                NoteRecord(
                    id = it.id, folderId = folderIn(it.folderId, "NOTES"), title = it.title,
                    description = it.description, body = it.body, createdAt = it.createdAt,
                    updatedAt = it.updatedAt, isFavorite = it.isFavorite, isPinned = it.isPinned,
                    colorIndex = it.colorIndex, fields = noteFields[it.id].orEmpty()
                )
            },
            credentials = logins.map {
                CredentialRecord(
                    id = it.id, folderId = folderIn(it.folderId, "PASSWORDS"), serviceName = it.serviceName,
                    websiteUrl = it.websiteUrl, username = it.username, password = it.password,
                    notes = it.notes, isFavorite = it.isFavorite, createdAt = it.createdAt,
                    updatedAt = it.updatedAt, category = it.category, fields = it.fields
                )
            },
            tags = snapshot.tags.map { TagRecord(it.id, it.name, it.createdAt) },
            // Links of items in the trash are left out with the items (the reader drops them anyway).
            tagLinks = run {
                val active = mapOf(
                    "NOTE" to snapshot.notes.mapTo(HashSet()) { it.id },
                    "LOGIN" to logins.mapTo(HashSet()) { it.id },
                    "FILE" to snapshot.files.mapTo(HashSet()) { it.id }
                )
                snapshot.tagLinks.filter { active[it.itemType]?.contains(it.itemId) == true }
                    .map { TagLinkRecord(it.tagId, it.itemType, it.itemId) }
            },
            categories = snapshot.categories.map { CategoryRecord(it.id, it.name, it.icon, it.fieldsJson, it.createdAt) },
            files = snapshot.files.map {
                FileRecord(
                    id = it.id, folderId = folderIn(it.folderId, "FILES"), originalName = it.originalName,
                    displayName = it.displayName, mimeType = it.mimeType, sizeBytes = it.sizeBytes,
                    keywords = it.keywords, description = it.description, isFavorite = it.isFavorite,
                    createdAt = it.createdAt, updatedAt = it.updatedAt
                )
            },
            settings = prefs
        )
    }

    /** Preferences that are worth carrying over. Lock and biometric state are device specific. */
    private suspend fun currentSettings(): Map<String, String> {
        val current = settings.settings.first()
        return mapOf(
            KEY_THEME to current.themeMode.name,
            KEY_FONT_SIZE to current.fontSize.name,
            KEY_FONT to current.fontKey,
            KEY_AUTO_LOCK to current.autoLockTimeout.name,
            KEY_CLIPBOARD to current.clipboardClearTimeout.name,
            KEY_HIDE_IN_RECENTS to current.hideInRecents.toString()
        )
    }

    private fun deletePartialDocument(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (e: Exception) {
            // The provider may not allow it; the user can delete the file themselves.
        }
    }

    // ---------- restore ----------

    /**
     * Decrypts, checks and unpacks the backup at [uri] into private staging storage, and only then
     * replaces all current notes, logins, files, folders and preferences with it. Nothing current is
     * changed unless every check passes. Throws [BackupException].
     */
    suspend fun restore(uri: Uri, passphrase: CharArray, onPhase: (BackupPhase) -> Unit): RestoreSummary =
        withContext(Dispatchers.IO) {
            // Held from staging to commit, so a recovery started meanwhile cannot touch the staging.
            RestoreCommitter.exclusive { restoreLocked(uri, passphrase, onPhase) }
        }

    private suspend fun restoreLocked(uri: Uri, passphrase: CharArray, onPhase: (BackupPhase) -> Unit): RestoreSummary {
        try {
            recoverLocked()
        } catch (e: Exception) {
            // An earlier interrupted restore could not be completed or undone yet: change nothing.
            throw BackupException(BackupError.FAILED)
        }
        onPhase(BackupPhase.DECRYPTING)
        val staging = committer.newStagingDir()
        var committed = false
        try {
            val scope = currentCoroutineContext()
            val input = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            } ?: throw BackupException(BackupError.CANNOT_READ)

            val staged = BufferedInputStream(input, 64 * 1024).use { buffered ->
                val header = BackupHeader.read(buffered)
                val key = BackupKeys.derive(passphrase, header)
                BackupArchiveReader(checkCancelled = { scope.ensureActive() }).read(
                    input = DecryptingInputStream(buffered, key, header),
                    stagingDir = staging,
                    ensureSpace = { needed ->
                        if (filesDir.usableSpace < needed + SPACE_RESERVE_BYTES) {
                            throw BackupException(BackupError.NOT_ENOUGH_STORAGE)
                        }
                    }
                )
            }

            onPhase(BackupPhase.APPLYING)
            val contents = staged.contents
            val entities: List<CredentialEntity>
            val noteFieldEntities: List<NoteFieldsEntity>
            try {
                entities = contents.credentials.map { credentials.encryptForRestore(it.toDetail()) }
                noteFieldEntities = contents.notes.mapNotNull { notes.encryptFieldsForRestore(it.id, it.fields) }
            } catch (e: FieldCipherException) {
                throw BackupException(BackupError.FAILED)
            }
            val fingerprint = RestoreCommitter.fingerprint(
                contents.folders.map { it.id },
                contents.notes.map { RestoreCommitter.noteKey(it.id, it.updatedAt, it.body) },
                contents.credentials.map { it.id }, contents.files.map { it.id }
            )
            // Past this point the restore is not cancelled half way.
            withContext(NonCancellable) {
                committer.commit(listOf(staged.stagedFiles, staged.stagedImages), fingerprint) {
                    database.withTransaction {
                        database.tagDao().deleteAllLinks()
                        database.tagDao().deleteAll()
                        database.categoryDao().deleteAll()
                        database.noteDao().deleteAll() // note fields go with their notes (cascade)
                        database.credentialDao().deleteAll()
                        database.fileDao().deleteAll()
                        database.folderDao().deleteAll()
                        database.folderDao().insertAll(parentsFirst(contents.folders).map {
                            FolderEntity(it.id, it.module, it.name, it.parentId, it.createdAt)
                        })
                        database.noteDao().insertAll(contents.notes.map { it.toEntity() })
                        database.noteDao().insertAllFields(noteFieldEntities)
                        database.credentialDao().insertAll(entities)
                        database.fileDao().insertAll(contents.files.map { it.toEntity(vault) })
                        database.categoryDao().insertAll(contents.categories.map {
                            CategoryEntity(it.id, it.name, it.icon, it.fieldsJson, it.createdAt)
                        })
                        database.tagDao().insertAll(contents.tags.map {
                            TagEntity(it.id, TagNames.clean(it.name), TagNames.normalize(it.name), it.createdAt)
                        })
                        database.tagDao().insertAllLinks(contents.tagLinks.map { ItemTagEntity(it.tagId, it.itemType, it.itemId) })
                    }
                }
                committed = true
                credentials.clearCache()
                applySettings(contents.settings)
                ShareStaging.clear(context)
            }
            return RestoreSummary(
                notes = contents.notes.size,
                logins = contents.credentials.size,
                files = contents.files.size,
                folders = contents.folders.size,
                noteImages = staged.noteImageCount.coerceAtLeast(0),
                missingFiles = staged.missingFiles.size,
                skippedLogins = staged.skippedLogins
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: SecurityException) {
            throw BackupException(BackupError.CANNOT_READ)
        } catch (e: Exception) {
            throw BackupException(BackupError.FAILED)
        } finally {
            if (!committed) committer.discardStaging()
        }
    }

    /** Finishes or undoes a restore that was interrupted (for example by the app being killed). */
    suspend fun recoverInterruptedRestore() = withContext(Dispatchers.IO) {
        RestoreCommitter.exclusive { recoverLocked() }
    }

    private suspend fun recoverLocked() {
        committer.recover {
            database.withTransaction {
                RestoreCommitter.fingerprint(
                    database.folderDao().getAll().map { it.id },
                    database.noteDao().getAll().map { RestoreCommitter.noteKey(it.id, it.updatedAt, it.body) },
                    database.credentialDao().getAll().map { it.id },
                    database.fileDao().getAll().map { it.id }
                )
            }
        }
    }

    private suspend fun applySettings(values: Map<String, String>) {
        try {
            values[KEY_THEME]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } }?.let { settings.setThemeMode(it) }
            values[KEY_FONT_SIZE]?.let { v -> FontSize.entries.firstOrNull { it.name == v } }?.let { settings.setFontSize(it) }
            values[KEY_FONT]?.takeIf { it.isNotBlank() && it.length <= 64 }?.let { settings.setFontKey(it) }
            values[KEY_AUTO_LOCK]?.let { v -> AutoLockTimeout.entries.firstOrNull { it.name == v } }
                ?.let { settings.setAutoLockTimeout(it) }
            values[KEY_CLIPBOARD]?.let { v -> ClipboardClearTimeout.entries.firstOrNull { it.name == v } }
                ?.let { settings.setClipboardClearTimeout(it) }
            values[KEY_HIDE_IN_RECENTS]?.toBooleanStrictOrNull()?.let { settings.setHideInRecents(it) }
        } catch (e: Exception) {
            // Preferences are not essential; the data itself has been restored.
        }
    }

    companion object {
        private const val SPACE_RESERVE_BYTES = 20L * 1024 * 1024
        private const val KEY_THEME = "themeMode"
        private const val KEY_FONT_SIZE = "fontSize"
        private const val KEY_FONT = "fontKey"
        private const val KEY_AUTO_LOCK = "autoLockTimeout"
        private const val KEY_CLIPBOARD = "clipboardClearTimeout"
        private const val KEY_HIDE_IN_RECENTS = "hideInRecents"

        fun create(context: Context): BackupService {
            val app = context.applicationContext
            return BackupService(app, TazDatabase.getInstance(app), KeystoreFieldCipher(), AppContainer.settings(app))
        }

        /** Folders ordered so that every parent comes before its children (the reader rejects loops). */
        internal fun parentsFirst(folders: List<FolderRecord>): List<FolderRecord> {
            val byParent = folders.groupBy { it.parentId }
            val ordered = ArrayList<FolderRecord>(folders.size)
            val queue = ArrayDeque(byParent[null].orEmpty())
            while (queue.isNotEmpty()) {
                val folder = queue.removeFirst()
                ordered += folder
                queue.addAll(byParent[folder.id].orEmpty())
            }
            return ordered
        }
    }
}

private fun CredentialRecord.toDetail() = CredentialDetail(
    id = id, folderId = folderId, serviceName = serviceName, username = username, password = password,
    websiteUrl = websiteUrl, notes = notes, isFavorite = isFavorite, createdAt = createdAt, updatedAt = updatedAt,
    category = category, fields = fields
)

private fun NoteRecord.toEntity() = NoteEntity(
    id = id, folderId = folderId, title = title, description = description, body = body,
    createdAt = createdAt, updatedAt = updatedAt, isFavorite = isFavorite, isPinned = isPinned,
    colorIndex = colorIndex
)

/** The stored path is always rebuilt from the id, never taken from the backup. */
private fun FileRecord.toEntity(vault: VaultFileStore) = FileEntity(
    id = id, folderId = folderId, originalName = originalName, displayName = displayName,
    mimeType = mimeType, sizeBytes = sizeBytes, storedPath = vault.relativePathFor(id), keywords = keywords,
    description = description, isFavorite = isFavorite, createdAt = createdAt, updatedAt = updatedAt
)
