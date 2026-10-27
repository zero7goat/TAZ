package com.zero7goat.tung.core.backup

import com.zero7goat.tung.features.notes.data.NoteImageStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val imageName = "0f8fad5b-d9cb-469f-a165-70867728950e.jpg"
    private val missingImage = "7c9e6679-7425-40de-944b-e07fc1f90ae7.png"
    private val passphrase = "synthetic test passphrase"

    private fun sampleContents(withMissingImage: Boolean = false) = BackupContents(
        folders = listOf(
            FolderRecord("f-root", "NOTES", "Work", null, 1L),
            FolderRecord("f-child", "NOTES", "Projects ✨", "f-root", 2L),
            FolderRecord("f-files", "FILES", "Scans", null, 3L),
            FolderRecord("f-logins", "PASSWORDS", "Banking", null, 4L)
        ),
        notes = listOf(
            NoteRecord(
                id = "n1", folderId = "f-child", title = "Plan", description = "Q4",
                body = "**bold** *it* ==hi==\n> quote\n- [x] done\n[link](https://example.com)\n" +
                    NoteImageStore.markdownFor(imageName) +
                    (if (withMissingImage) "\n" + NoteImageStore.markdownFor(missingImage) else ""),
                createdAt = 10L, updatedAt = 20L, isFavorite = true, isPinned = false, colorIndex = 3
            ),
            NoteRecord("n2", null, "", "", "", 1L, 1L, false, true, 0)
        ),
        credentials = listOf(
            CredentialRecord("c1", "f-logins", "Bank", "https://bank.example", "alice", "hunter2-marker", "PIN 1234", true, 5L, 6L)
        ),
        files = listOf(
            FileRecord("file-1", "f-files", "scan.pdf", "scan.pdf", "application/pdf", 3, "tax, 2026", "Receipt", false, 7L, 8L),
            FileRecord("file-2", null, "gone.txt", "gone.txt", "text/plain", 5, "", "", true, 9L, 9L)
        ),
        settings = mapOf("themeMode" to "DARK", "hideInRecents" to "true")
    )

    /** Writes a full encrypted backup to a byte array. "file-2" has no stored copy. */
    private fun backup(
        contents: BackupContents = sampleContents(),
        fileBlobs: Map<String, File> = mapOf("file-1" to temp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }),
        images: Map<String, File> = mapOf(imageName to temp.newFile().apply { writeBytes(byteArrayOf(9, 9)) })
    ): Pair<ByteArray, BackupSummary> {
        val header = BackupHeader.create(iterations = 1_000)
        val out = ByteArrayOutputStream()
        val summary = EncryptingOutputStream(out, BackupKeys.derive(passphrase.toCharArray(), header), header).use {
            BackupArchiveWriter().write(it, contents, { r -> fileBlobs[r.id] }, { n -> images[n] }, createdAt = 42L)
        }
        return out.toByteArray() to summary
    }

    private fun restore(file: ByteArray, secret: String = passphrase, staging: File = temp.newFolder()): StagedBackup {
        val input = ByteArrayInputStream(file)
        val header = BackupHeader.read(input, minIterations = 1)
        return BackupArchiveReader().read(DecryptingInputStream(input, BackupKeys.derive(secret.toCharArray(), header), header), staging)
    }

    private fun readZip(zip: ByteArray, staging: File = temp.newFolder()) =
        BackupArchiveReader(maxJsonBytes = 4096).read(ByteArrayInputStream(zip), staging)

    private fun assertFails(expected: BackupError, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (e: BackupException) {
            assertEquals(expected, e.error)
        }
    }

    // ---------- round trip ----------

    @Test
    fun fullBackupRoundTripsEverything() {
        val contents = sampleContents()
        val (file, summary) = backup(contents)
        assertEquals(1, summary.missingFiles)
        assertEquals(1, summary.noteImages)

        val staged = restore(file)
        assertEquals(contents.folders, staged.contents.folders)
        assertEquals(contents.notes, staged.contents.notes)
        assertEquals(contents.files, staged.contents.files)
        assertEquals(contents.settings, staged.contents.settings)
        val login = staged.contents.credentials.single()
        assertEquals("alice", login.username)
        assertEquals("hunter2-marker", login.password)
        assertEquals("PIN 1234", login.notes)
        assertEquals("f-logins", login.folderId)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(staged.stagedFiles, "file-1").readBytes())
        assertArrayEquals(byteArrayOf(9, 9), File(staged.stagedImages, imageName).readBytes())
        assertEquals(setOf("file-2"), staged.missingFiles)
        assertEquals(42L, staged.createdAt)
    }

    @Test
    fun backupFileNeverContainsPlaintextSecrets() {
        val (file, _) = backup()
        val text = String(file, Charsets.ISO_8859_1)
        assertFalse(text.contains("hunter2-marker"))
        assertFalse(text.contains("alice"))
        assertFalse(text.contains("credentials.json"))
    }

    @Test
    fun wrongPassphraseRestoresNothing() {
        val (file, _) = backup()
        val staging = temp.newFolder()
        assertFails(BackupError.WRONG_PASSPHRASE) { restore(file, "not the passphrase", staging) }
    }

    @Test
    fun corruptedBackupIsRejected() {
        val (file, _) = backup()
        file[file.size / 2] = (file[file.size / 2].toInt() xor 0x40).toByte()
        try {
            restore(file)
            fail("Expected failure")
        } catch (e: BackupException) {
            assertTrue(e.error == BackupError.DAMAGED || e.error == BackupError.WRONG_PASSPHRASE)
        }
    }

    @Test
    fun missingAttachmentsAreRecordedAndDoNotBlockRestore() {
        val (file, summary) = backup(sampleContents(withMissingImage = true))
        assertEquals(1, summary.missingNoteImages)
        val staged = restore(file)
        assertEquals(setOf(missingImage), staged.missingNoteImages)
        assertFalse(File(staged.stagedImages, missingImage).exists())
        // The note still references it; the editor shows "Image not available".
        assertTrue(staged.contents.notes.first().body.contains(missingImage))
    }

    @Test
    fun largeFilesAreStreamedIntact() {
        val big = temp.newFile()
        val data = ByteArray(6 * 1024 * 1024 + 123).also { SecureRandom().nextBytes(it) }
        big.writeBytes(data)
        val contents = sampleContents().copy(
            files = listOf(FileRecord("big-1", null, "video.mp4", "video.mp4", "video/mp4", data.size.toLong(), "", "", false, 1L, 1L))
        )
        val (file, _) = backup(contents, fileBlobs = mapOf("big-1" to big))
        val staged = restore(file)
        assertArrayEquals(data, File(staged.stagedFiles, "big-1").readBytes())
    }

    @Test
    fun aFileThatChangesDuringBackupFailsTheBackup() {
        val growing = temp.newFile().apply { writeBytes(byteArrayOf(1)) }
        val contents = sampleContents().copy(files = listOf(sampleContents().files.first()))
        val header = BackupHeader.create(iterations = 1_000)
        assertFails(BackupError.CANNOT_READ) {
            EncryptingOutputStream(ByteArrayOutputStream(), BackupKeys.derive("x".toCharArray(), header), header).use {
                var changed = false
                BackupArchiveWriter(checkCancelled = { if (!changed) { changed = true; growing.appendBytes(byteArrayOf(2)) } })
                    .write(it, contents, { growing }, { null }, 1L)
            }
        }
    }

    @Test
    fun notEnoughSpaceIsCheckedBeforeUnpacking() {
        val (file, _) = backup()
        val input = ByteArrayInputStream(file)
        val header = BackupHeader.read(input, minIterations = 1)
        val staging = temp.newFolder()
        assertFails(BackupError.NOT_ENOUGH_STORAGE) {
            BackupArchiveReader().read(
                DecryptingInputStream(input, BackupKeys.derive(passphrase.toCharArray(), header), header),
                staging,
                ensureSpace = { throw BackupException(BackupError.NOT_ENOUGH_STORAGE) }
            )
        }
        assertEquals(0, File(staging, "files").listFiles()!!.size)
    }

    // ---------- hand-made archives ----------

    private fun manifest(blobs: Map<String, Long> = emptyMap(), version: Int = 1, missingFiles: List<String> = emptyList()) =
        JSONObject()
            .put("format", "taz-backup")
            .put("version", version)
            .put("createdAt", 1L)
            .put("blobs", JSONArray().also { a -> blobs.forEach { (p, s) -> a.put(JSONObject().put("path", p).put("size", s)) } })
            .put("missingFiles", JSONArray(missingFiles))
            .toString()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun emptyData(files: String = "[]", folders: String = "[]") = arrayOf(
        "data/folders.json" to folders.toByteArray(),
        "data/notes.json" to "[]".toByteArray(),
        "data/credentials.json" to "[]".toByteArray(),
        "data/files.json" to files.toByteArray(),
        "data/settings.json" to "{}".toByteArray()
    )

    private fun fileJson(id: String) =
        """[{"id":"$id","folderId":null,"originalName":"a","displayName":"a","mimeType":"x","sizeBytes":1,
            "keywords":"","description":"","isFavorite":false,"createdAt":1,"updatedAt":1}]"""

    /** Encrypts [plain] in 1 KiB chunks so the archive and the last chunk are separate. */
    private fun encryptSmallChunks(plain: ByteArray): ByteArray {
        val header = BackupHeader(1_000, ByteArray(16) { it.toByte() }, ByteArray(8) { 7 }, chunkSize = 1024)
        val out = ByteArrayOutputStream()
        EncryptingOutputStream(out, BackupKeys.derive(passphrase.toCharArray(), header), header).use { it.write(plain) }
        return out.toByteArray()
    }

    @Test
    fun theLastChunkIsVerifiedEvenAfterTheArchiveEnds() {
        // A complete archive followed by more data: the reader must still authenticate the end.
        val plain = zip("manifest.json" to manifest().toByteArray(), *emptyData()) + ByteArray(3000)
        val intact = encryptSmallChunks(plain)
        restore(intact) // padding after the archive is accepted when authentic

        val tampered = intact.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        assertFails(BackupError.DAMAGED) { restore(tampered) }

        val remainder = plain.size % 1024
        val lastChunkBytes = 5 + (if (remainder == 0) 1024 else remainder) + 16 // flag, length, data, tag
        assertFails(BackupError.INCOMPLETE) { restore(intact.copyOf(intact.size - lastChunkBytes)) }
    }

    @Test
    fun minimalValidArchiveIsAccepted() {
        val staged = readZip(zip("manifest.json" to manifest().toByteArray(), *emptyData()))
        assertTrue(staged.contents.notes.isEmpty())
    }

    @Test
    fun pathTraversalAndUnknownEntriesAreRejected() {
        listOf("../evil.txt", "/etc/passwd", "data/../../evil", "blobs/files/../../evil", "blobs/files/a/b", "extra.txt")
            .forEach { name ->
                val staging = temp.newFolder()
                assertFails(BackupError.INVALID_CONTENT) {
                    readZip(zip("manifest.json" to manifest().toByteArray(), name to byteArrayOf(1), *emptyData()), staging)
                }
                assertFalse(File(staging.parentFile, "evil.txt").exists())
                assertFalse(File(staging.parentFile!!.parentFile, "evil").exists())
            }
    }

    @Test
    fun manifestWithUnsafeBlobPathsIsRejected() {
        listOf("blobs/files/../x", "blobs/notes/evil.jpg", "blobs/files/", "other/x").forEach { path ->
            assertFails(BackupError.INVALID_CONTENT) {
                readZip(zip("manifest.json" to manifest(mapOf(path to 1L)).toByteArray(), *emptyData()))
            }
        }
    }

    @Test
    fun undeclaredBlobIsRejected() {
        assertFails(BackupError.INVALID_CONTENT) {
            readZip(zip("manifest.json" to manifest().toByteArray(), "blobs/files/abc" to byteArrayOf(1), *emptyData()))
        }
    }

    @Test
    fun blobLargerThanDeclaredIsRejected() {
        // Protects against decompression bombs: reading stops right after the declared size.
        val bomb = ByteArray(1_000_000)
        assertFails(BackupError.INVALID_CONTENT) {
            readZip(
                zip(
                    "manifest.json" to manifest(mapOf("blobs/files/abc" to 10L)).toByteArray(),
                    "blobs/files/abc" to bomb, *emptyData(files = fileJson("abc"))
                )
            )
        }
    }

    @Test
    fun blobSmallerThanDeclaredIsIncomplete() {
        assertFails(BackupError.INCOMPLETE) {
            readZip(
                zip(
                    "manifest.json" to manifest(mapOf("blobs/files/abc" to 10L)).toByteArray(),
                    "blobs/files/abc" to byteArrayOf(1), *emptyData(files = fileJson("abc"))
                )
            )
        }
    }

    @Test
    fun declaredBlobThatIsAbsentIsIncomplete() {
        assertFails(BackupError.INCOMPLETE) {
            readZip(zip("manifest.json" to manifest(mapOf("blobs/files/abc" to 1L)).toByteArray(), *emptyData(files = fileJson("abc"))))
        }
    }

    @Test
    fun fileRecordWithoutContentsIsIncompleteUnlessRecordedMissing() {
        assertFails(BackupError.INCOMPLETE) {
            readZip(zip("manifest.json" to manifest().toByteArray(), *emptyData(files = fileJson("abc"))))
        }
        val staged = readZip(zip("manifest.json" to manifest(missingFiles = listOf("abc")).toByteArray(), *emptyData(files = fileJson("abc"))))
        assertEquals("abc", staged.contents.files.single().id)
    }

    @Test
    fun missingDataEntryIsIncomplete() {
        assertFails(BackupError.INCOMPLETE) {
            readZip(zip("manifest.json" to manifest().toByteArray(), *emptyData().drop(1).toTypedArray()))
        }
    }

    @Test
    fun manifestMustComeFirst() {
        assertFails(BackupError.INVALID_CONTENT) {
            readZip(zip(*emptyData(), "manifest.json" to manifest().toByteArray()))
        }
    }

    @Test
    fun unsupportedContentVersionIsRejected() {
        assertFails(BackupError.UNSUPPORTED_VERSION) {
            readZip(zip("manifest.json" to manifest(version = 2).toByteArray(), *emptyData()))
        }
    }

    @Test
    fun oversizedJsonIsRejected() {
        val huge = "[" + (1..2000).joinToString(",") { "\"x\"" } + "]"
        assertFails(BackupError.TOO_LARGE) {
            readZip(zip("manifest.json" to manifest().toByteArray(), "data/folders.json" to huge.toByteArray()))
        }
    }

    @Test
    fun malformedJsonIsRejected() {
        val data = emptyData()
        data[1] = "data/notes.json" to "{not json".toByteArray()
        assertFails(BackupError.INVALID_CONTENT) { readZip(zip("manifest.json" to manifest().toByteArray(), *data)) }
    }

    @Test
    fun folderLoopsAndCrossModuleParentsAreRejected() {
        val loop = """[{"id":"a","module":"NOTES","name":"A","parentId":"b","createdAt":1},
                       {"id":"b","module":"NOTES","name":"B","parentId":"a","createdAt":1}]"""
        assertFails(BackupError.INVALID_CONTENT) {
            readZip(zip("manifest.json" to manifest().toByteArray(), *emptyData(folders = loop)))
        }
        val crossModule = """[{"id":"a","module":"FILES","name":"A","parentId":null,"createdAt":1},
                              {"id":"b","module":"NOTES","name":"B","parentId":"a","createdAt":1}]"""
        assertFails(BackupError.INVALID_CONTENT) {
            readZip(zip("manifest.json" to manifest().toByteArray(), *emptyData(folders = crossModule)))
        }
    }

    @Test
    fun foldersAreOrderedParentsFirstForInsertion() {
        val folders = listOf(
            FolderRecord("c", "NOTES", "C", "b", 1),
            FolderRecord("b", "NOTES", "B", "a", 1),
            FolderRecord("a", "NOTES", "A", null, 1),
            FolderRecord("x", "FILES", "X", null, 1)
        )
        val ordered = BackupService.parentsFirst(folders).map { it.id }
        assertEquals(4, ordered.size)
        assertTrue(ordered.indexOf("a") < ordered.indexOf("b"))
        assertTrue(ordered.indexOf("b") < ordered.indexOf("c"))
    }
}
