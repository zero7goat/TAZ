package com.zero7goat.tung.core.backup

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Applies a staged restore so that the database and the stored files always end up matching:
 * either everything from the backup, or everything as it was before. Pure JVM code so it can be
 * unit tested.
 *
 * Steps (all directories are in the app's private storage):
 *  1. A journal file is written holding a fingerprint of the restored data (record ids).
 *  2. Each live directory (stored files, note images) is renamed to `previous/`, and the staged
 *     directory takes its place. Renames within one file system are atomic.
 *  3. The database is replaced in one transaction ([replaceDatabase]).
 *  4. The journal and the previous directories are deleted.
 *
 * If step 2 or 3 throws, the directories are swapped back and the database is untouched (the
 * transaction rolled back). If the process dies part way, [recover] (run at the next start)
 * compares the database's fingerprint with the journal: if the transaction committed it finishes
 * the restore, otherwise it puts the previous directories back.
 *
 * Stored files and note images are named by ids that never change, so the contents behind a
 * given name are the same before and after a restore of the same item.
 */
class RestoreCommitter(
    filesDir: File,
    /** Live directory relative path (under [filesDir]) to staged directory name. */
    private val liveDirs: List<String> = listOf(FILES_DIR, NOTE_IMAGES_DIR)
) {
    private val root = filesDir
    private val workDir = File(filesDir, WORK_DIR)
    private val previousDir = File(workDir, "previous")
    private val journal = File(workDir, "journal")

    /** A fresh, empty staging directory for unpacking a backup. Removes any earlier one. */
    fun newStagingDir(): File {
        val staging = File(workDir, "staging")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw BackupException(BackupError.NOT_ENOUGH_STORAGE)
        return staging
    }

    fun discardStaging() {
        File(workDir, "staging").deleteRecursively()
    }

    /**
     * Swaps in [stagedDirs] (same order as the live directories) and runs [replaceDatabase].
     * [fingerprint] must be what [currentFingerprint] in [recover] will return once the database
     * holds the restored data.
     */
    suspend fun commit(stagedDirs: List<File>, fingerprint: String, replaceDatabase: suspend () -> Unit) {
        require(stagedDirs.size == liveDirs.size)
        check(!journal.exists()) { "A previous restore was not finished" }
        previousDir.deleteRecursively()
        if (!previousDir.mkdirs()) throw BackupException(BackupError.NOT_ENOUGH_STORAGE)
        writeJournal(fingerprint)

        try {
            liveDirs.forEachIndexed { index, relative ->
                val live = File(root, relative)
                // An empty live directory is still moved aside so a rollback leaves no new files behind.
                if (!live.isDirectory && !live.mkdirs()) throw IOException("Cannot prepare $relative")
                if (!live.renameTo(previousFor(relative))) throw IOException("Cannot move $relative aside")
                val staged = stagedDirs[index]
                if (!staged.isDirectory && !staged.mkdirs()) throw IOException("Cannot prepare staged data")
                if (!staged.renameTo(live)) throw IOException("Cannot move staged data into place")
            }
            replaceDatabase()
        } catch (e: Throwable) {
            rollBack()
            throw e
        }
        finish()
    }

    /**
     * Completes or undoes a restore that was interrupted. [currentFingerprint] computes the
     * fingerprint of what the database holds now. Does nothing if no restore was in progress.
     */
    suspend fun recover(currentFingerprint: suspend () -> String) {
        if (!journal.exists()) {
            previousDir.deleteRecursively()
            discardStaging()
            return
        }
        val expected = try {
            journal.readText()
        } catch (e: IOException) {
            ""
        }
        if (expected.isNotEmpty() && currentFingerprint() == expected) finish() else rollBack()
    }

    /** True if a journal exists, meaning a restore was interrupted and [recover] should run. */
    fun needsRecovery(): Boolean = journal.exists()

    private fun previousFor(relative: String) = File(previousDir, relative.replace('/', '_'))

    private fun rollBack() {
        liveDirs.forEach { relative ->
            val previous = previousFor(relative)
            if (previous.exists()) {
                val live = File(root, relative)
                live.deleteRecursively()
                live.parentFile?.mkdirs()
                previous.renameTo(live)
            }
        }
        journal.delete()
        previousDir.deleteRecursively()
        discardStaging()
    }

    private fun finish() {
        journal.delete()
        previousDir.deleteRecursively()
        discardStaging()
    }

    private fun writeJournal(fingerprint: String) {
        val temp = File(workDir, "journal.tmp")
        temp.writeText(fingerprint)
        if (!temp.renameTo(journal)) throw BackupException(BackupError.NOT_ENOUGH_STORAGE)
    }

    companion object {
        const val WORK_DIR = "vault/restore"
        const val FILES_DIR = "vault/files"
        const val NOTE_IMAGES_DIR = "vault/note_images"

        /** A hash of every record id per table: identifies which data the database holds. */
        fun fingerprint(
            folderIds: Collection<String>,
            noteIds: Collection<String>,
            credentialIds: Collection<String>,
            fileIds: Collection<String>
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf("folders" to folderIds, "notes" to noteIds, "credentials" to credentialIds, "files" to fileIds)
                .forEach { (table, ids) ->
                    digest.update("$table:${ids.size}\n".toByteArray())
                    ids.sorted().forEach { digest.update("$it\n".toByteArray()) }
                }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
