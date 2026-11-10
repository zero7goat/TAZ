package com.zero7goat.taz.features.trash.data

import android.content.Context
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.database.TrashRow
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import com.zero7goat.taz.core.storage.VaultFileStore
import com.zero7goat.taz.features.files.data.FileRepository
import com.zero7goat.taz.features.notes.data.NoteImageStore
import com.zero7goat.taz.features.notes.data.NoteRepository
import com.zero7goat.taz.features.passwords.data.CredentialRepository
import com.zero7goat.taz.features.trash.domain.TrashItem
import com.zero7goat.taz.features.trash.domain.TrashKind
import com.zero7goat.taz.features.trash.domain.TrashRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The trash for notes, logins and files. Deleting an item elsewhere in the app only moves it here;
 * only [purge], [emptyTrash] and [purgeExpired] remove anything for good.
 */
class TrashRepository(
    private val notes: NoteRepository,
    private val credentials: CredentialRepository,
    private val files: FileRepository,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /** Everything in the trash, most recently deleted first. */
    fun observe(): Flow<List<TrashItem>> =
        combine(notes.observeTrash(), credentials.observeTrash(), files.observeTrash()) { n, c, f ->
            (n.toItems(TrashKind.NOTE) + c.toItems(TrashKind.LOGIN) + f.toItems(TrashKind.FILE))
                .sortedByDescending { it.deletedAt }
        }

    suspend fun restore(item: TrashItem) {
        when (item.kind) {
            TrashKind.NOTE -> notes.restoreNote(item.id)
            TrashKind.LOGIN -> credentials.restore(item.id)
            TrashKind.FILE -> files.restore(item.id)
        }
    }

    suspend fun purge(item: TrashItem) = purge(item.kind, item.id)

    /** Permanently deletes everything currently in the trash. Returns how many items were removed. */
    suspend fun emptyTrash(): Int {
        var count = 0
        notes.trashedIds().forEach { notes.purgeNote(it); count++ }
        credentials.trashedIds().forEach { credentials.purge(it); count++ }
        files.trashedIds().forEach { files.purge(it); count++ }
        return count
    }

    /** Permanently deletes items that have been in the trash longer than the retention period. */
    suspend fun purgeExpired(): Int {
        val cutoff = TrashRules.expiryCutoff(now())
        var count = 0
        notes.trashedBefore(cutoff).forEach { notes.purgeNote(it); count++ }
        credentials.trashedBefore(cutoff).forEach { credentials.purge(it); count++ }
        files.trashedBefore(cutoff).forEach { files.purge(it); count++ }
        return count
    }

    private suspend fun purge(kind: TrashKind, id: String) {
        when (kind) {
            TrashKind.NOTE -> notes.purgeNote(id)
            TrashKind.LOGIN -> credentials.purge(id)
            TrashKind.FILE -> files.purge(id)
        }
    }

    private fun List<TrashRow>.toItems(kind: TrashKind) =
        map { TrashItem(kind = kind, id = it.id, label = it.label, deletedAt = it.deletedAt) }

    companion object {
        fun create(context: Context): TrashRepository {
            val app = context.applicationContext
            val database = TazDatabase.getInstance(app)
            return TrashRepository(
                notes = NoteRepository(database.noteDao(), NoteImageStore(app.filesDir)),
                credentials = CredentialRepository(database.credentialDao(), KeystoreFieldCipher()),
                files = FileRepository(database.fileDao(), VaultFileStore(app.filesDir), app)
            )
        }
    }
}
