package com.zero7goat.taz.core.backup

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreCommitterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var committer: RestoreCommitter

    private val liveFiles get() = File(filesDir, RestoreCommitter.FILES_DIR)
    private val liveImages get() = File(filesDir, RestoreCommitter.NOTE_IMAGES_DIR)

    @Before
    fun setUp() {
        filesDir = temp.newFolder("files")
        committer = RestoreCommitter(filesDir)
        liveFiles.mkdirs()
        File(liveFiles, "old-file").writeText("old")
        liveImages.mkdirs()
        File(liveImages, "old-image.jpg").writeText("old image")
    }

    /** Staging with one new file and one new image. */
    private fun stage(): List<File> {
        val staging = committer.newStagingDir()
        val files = File(staging, "files").apply { mkdirs() }
        File(files, "new-file").writeText("new")
        val images = File(staging, "notes").apply { mkdirs() }
        File(images, "new-image.jpg").writeText("new image")
        return listOf(files, images)
    }

    private fun names(dir: File) = dir.list()?.toSet().orEmpty()

    @Test
    fun successfulCommitSwapsInTheNewFiles() = runBlocking {
        var databaseReplaced = false
        committer.commit(stage(), "fp") { databaseReplaced = true }
        assertTrue(databaseReplaced)
        assertEquals(setOf("new-file"), names(liveFiles))
        assertEquals(setOf("new-image.jpg"), names(liveImages))
        assertFalse(committer.needsRecovery())
        assertFalse(File(filesDir, RestoreCommitter.WORK_DIR + "/previous").exists())
        assertFalse(File(filesDir, RestoreCommitter.WORK_DIR + "/staging").exists())
    }

    @Test
    fun failedDatabaseReplacementPutsTheOldFilesBack() = runBlocking {
        try {
            committer.commit(stage(), "fp") { throw IOException("disk full") }
            fail("Expected failure")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(setOf("old-file"), names(liveFiles))
        assertEquals("old", File(liveFiles, "old-file").readText())
        assertEquals(setOf("old-image.jpg"), names(liveImages))
        assertFalse(committer.needsRecovery())
        assertFalse(File(filesDir, RestoreCommitter.WORK_DIR + "/staging").exists())
    }

    @Test
    fun firstRestoreWithNoExistingFilesRollsBackCleanly() = runBlocking {
        liveFiles.deleteRecursively()
        liveImages.deleteRecursively()
        try {
            committer.commit(stage(), "fp") { throw IllegalStateException() }
            fail("Expected failure")
        } catch (e: IllegalStateException) {
            // expected
        }
        // No restored file is left behind without a database record.
        assertTrue(names(liveFiles).isEmpty())
        assertTrue(names(liveImages).isEmpty())
    }

    @Test
    fun repeatedRestoresAreIndependent() = runBlocking {
        committer.commit(stage(), "fp1") {}
        committer.commit(stage(), "fp2") {}
        assertEquals(setOf("new-file"), names(liveFiles))
        assertFalse(committer.needsRecovery())
    }

    /** Simulates the process dying after the directories were swapped. */
    private suspend fun interruptAfterSwap(fingerprint: String) {
        try {
            committer.commit(stage(), fingerprint) { throw KilledProcess() }
        } catch (e: KilledProcess) {
            // commit() already rolled back; recreate the on-disk state of a real crash instead.
        }
        val work = File(filesDir, RestoreCommitter.WORK_DIR)
        val previous = File(work, "previous").apply { mkdirs() }
        liveFiles.renameTo(File(previous, "vault_files"))
        liveImages.renameTo(File(previous, "vault_note_images"))
        liveFiles.mkdirs()
        File(liveFiles, "new-file").writeText("new")
        liveImages.mkdirs()
        File(work, "journal").writeText(fingerprint)
    }

    private class KilledProcess : RuntimeException()

    @Test
    fun recoveryRollsBackWhenTheDatabaseWasNotReplaced() = runBlocking {
        interruptAfterSwap("restored")
        assertTrue(committer.needsRecovery())
        committer.recover { "old database" }
        assertEquals(setOf("old-file"), names(liveFiles))
        assertEquals(setOf("old-image.jpg"), names(liveImages))
        assertFalse(committer.needsRecovery())
    }

    @Test
    fun recoveryCompletesWhenTheDatabaseWasReplaced() = runBlocking {
        interruptAfterSwap("restored")
        committer.recover { "restored" }
        assertEquals(setOf("new-file"), names(liveFiles))
        assertFalse(committer.needsRecovery())
        assertFalse(File(filesDir, RestoreCommitter.WORK_DIR + "/previous").exists())
    }

    @Test
    fun recoveryHandlesACrashBeforeTheNewFilesWereMovedIn() = runBlocking {
        val work = File(filesDir, RestoreCommitter.WORK_DIR)
        val previous = File(work, "previous").apply { mkdirs() }
        liveFiles.renameTo(File(previous, "vault_files")) // moved aside, nothing moved in yet
        File(work, "journal").writeText("restored")
        committer.recover { "old database" }
        assertEquals(setOf("old-file"), names(liveFiles))
    }

    @Test
    fun recoveryWithoutJournalOnlyCleansLeftovers() = runBlocking {
        committer.newStagingDir()
        var asked = false
        committer.recover { asked = true; "x" }
        assertFalse(asked)
        assertFalse(File(filesDir, RestoreCommitter.WORK_DIR + "/staging").exists())
        assertEquals(setOf("old-file"), names(liveFiles))
    }

    @Test
    fun missingStagingFailsAndKeepsTheOldFiles() = runBlocking {
        val staged = stage()
        staged[1].deleteRecursively() // e.g. removed by something else while the restore ran
        var databaseReplaced = false
        try {
            committer.commit(staged, "fp") { databaseReplaced = true }
            fail("Expected failure")
        } catch (e: IOException) {
            // expected
        }
        assertFalse(databaseReplaced)
        assertEquals(setOf("old-file"), names(liveFiles))
        assertEquals(setOf("old-image.jpg"), names(liveImages))
    }

    @Test
    fun oldDataIsNeverDeletedWhenItCannotBePutBack() = runBlocking {
        interruptAfterSwap("restored")
        // Keep a file in the live directory open: on Windows it then cannot be deleted, so the
        // previous directory cannot be moved back. (Where deletion succeeds, the rollback simply
        // completes.) Either way the old data must still exist afterwards.
        val blocker = File(liveImages, "busy.jpg").apply { writeText("x") }
        val handle = blocker.inputStream()
        try {
            try {
                committer.recover { "old database" }
            } catch (e: IOException) {
                // Rollback could not finish yet; the journal must remain for the next attempt.
                assertTrue(committer.needsRecovery())
            }
        } finally {
            handle.close()
        }
        val previousImages = File(filesDir, RestoreCommitter.WORK_DIR + "/previous/vault_note_images/old-image.jpg")
        assertTrue(File(liveImages, "old-image.jpg").exists() || previousImages.exists())
        assertEquals(setOf("old-file"), names(liveFiles))

        // Once nothing blocks it any more, the next recovery completes the rollback.
        committer.recover { "old database" }
        assertEquals(setOf("old-image.jpg"), names(liveImages))
        assertFalse(committer.needsRecovery())
    }

    @Test
    fun restoreAndRecoveryNeverRunAtTheSameTime() = runBlocking {
        var running = 0
        var overlapped = false
        val jobs = (1..4).map {
            async(Dispatchers.Default) {
                RestoreCommitter.exclusive {
                    if (++running > 1) overlapped = true
                    delay(20)
                    running--
                }
            }
        }
        jobs.awaitAll()
        assertFalse(overlapped)
    }

    @Test
    fun fingerprintDependsOnNoteBodies() {
        // Same ids, but the backup's note text differs (so it may reference other images).
        val current = RestoreCommitter.fingerprint(listOf("f"), listOf(RestoreCommitter.noteKey("n1", 5L, "![image](note-image:a.jpg)")), emptyList(), emptyList())
        val backup = RestoreCommitter.fingerprint(listOf("f"), listOf(RestoreCommitter.noteKey("n1", 5L, "older text")), emptyList(), emptyList())
        assertNotEquals(current, backup)
    }

    @Test
    fun fingerprintIgnoresOrderButNotContent() {
        val a = RestoreCommitter.fingerprint(listOf("f1", "f2"), listOf("n1"), emptyList(), listOf("x"))
        val b = RestoreCommitter.fingerprint(listOf("f2", "f1"), listOf("n1"), emptyList(), listOf("x"))
        val c = RestoreCommitter.fingerprint(listOf("f1", "f2"), emptyList(), listOf("n1"), listOf("x"))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
