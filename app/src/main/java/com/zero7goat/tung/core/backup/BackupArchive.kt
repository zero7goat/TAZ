package com.zero7goat.tung.core.backup

import com.zero7goat.tung.features.notes.data.NoteImageStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * The content of a backup: a ZIP archive that only ever exists inside the encrypted stream
 * (see BackupCrypto). Pure JVM code so it can be unit tested.
 *
 *     manifest.json            always the first entry: format, version, counts, the list of
 *                              file and image entries with their exact sizes, and anything that
 *                              was missing on the device when the backup was made
 *     data/folders.json        folders and subfolders of notes, logins and files
 *     data/notes.json          notes, including formatting (Markdown text) and metadata
 *     data/credentials.json    logins with decrypted username, password and notes
 *     data/files.json          file metadata (names, keywords, description, folder, ...)
 *     data/settings.json       non-sensitive preferences
 *     blobs/files/<id>         contents of each stored file
 *     blobs/notes/<name>       each note image
 *
 * The reader accepts nothing else: entry names must match these exact patterns, every blob must
 * be declared in the manifest with its size, and sizes are enforced while reading, so archive
 * paths are never used as file system paths and oversized ("zip bomb") entries are rejected.
 */

internal object BackupPaths {
    const val MANIFEST = "manifest.json"
    const val FOLDERS = "data/folders.json"
    const val NOTES = "data/notes.json"
    const val CREDENTIALS = "data/credentials.json"
    const val FILES = "data/files.json"
    const val SETTINGS = "data/settings.json"
    const val FILE_BLOB_PREFIX = "blobs/files/"
    const val NOTE_IMAGE_PREFIX = "blobs/notes/"
    val DATA_ENTRIES = listOf(FOLDERS, NOTES, CREDENTIALS, FILES, SETTINGS)

    const val FORMAT_NAME = "taz-backup"
    const val CONTENT_VERSION = 1

    private val SAFE_ID = Regex("^[A-Za-z0-9-]{1,64}$")

    fun isSafeFileId(id: String) = SAFE_ID.matches(id)
}

/** What a finished backup contains, for the success message. */
data class BackupSummary(
    val notes: Int,
    val logins: Int,
    val files: Int,
    val folders: Int,
    val noteImages: Int,
    val missingFiles: Int,
    val missingNoteImages: Int,
    val skippedLogins: Int
)

class BackupArchiveWriter(private val checkCancelled: () -> Unit = {}) {

    /**
     * Writes [contents] and the stored copies into [out] (which should be an encrypting stream).
     * [fileBlob] returns the stored copy of a file, or null if it is missing; [noteImage] the same
     * for a note image. Missing copies are recorded in the manifest instead of failing the backup.
     * [skippedLogins] is the number of logins that could not be decrypted and are not included.
     * Does not close [out].
     */
    fun write(
        out: OutputStream,
        contents: BackupContents,
        fileBlob: (FileRecord) -> File?,
        noteImage: (String) -> File?,
        createdAt: Long,
        skippedLogins: Int = 0
    ): BackupSummary {
        val fileBlobs = LinkedHashMap<String, File>()
        val missingFiles = ArrayList<String>()
        contents.files.forEach { record ->
            require(BackupPaths.isSafeFileId(record.id)) { "Unsafe file id" }
            val blob = fileBlob(record)?.takeIf { it.isFile }
            if (blob != null) fileBlobs[record.id] = blob else missingFiles += record.id
        }
        val imageNames = contents.notes.flatMapTo(sortedSetOf()) { NoteImageStore.referencedImageNames(it.body) }
        val images = LinkedHashMap<String, File>()
        val missingImages = ArrayList<String>()
        imageNames.forEach { name ->
            val file = noteImage(name)?.takeIf { it.isFile }
            if (file != null) images[name] = file else missingImages += name
        }

        val blobEntries = ArrayList<Pair<String, File>>()
        fileBlobs.forEach { (id, file) -> blobEntries += BackupPaths.FILE_BLOB_PREFIX + id to file }
        images.forEach { (name, file) -> blobEntries += BackupPaths.NOTE_IMAGE_PREFIX + name to file }
        val sizes = blobEntries.map { (path, file) -> path to file.length() }

        val manifest = JSONObject()
            .put("format", BackupPaths.FORMAT_NAME)
            .put("version", BackupPaths.CONTENT_VERSION)
            .put("createdAt", createdAt)
            .put(
                "counts",
                JSONObject()
                    .put("folders", contents.folders.size)
                    .put("notes", contents.notes.size)
                    .put("credentials", contents.credentials.size)
                    .put("files", contents.files.size)
            )
            .put("blobs", JSONArray().also { array ->
                sizes.forEach { (path, size) -> array.put(JSONObject().put("path", path).put("size", size)) }
            })
            .put("missingFiles", JSONArray(missingFiles))
            .put("missingNoteImages", JSONArray(missingImages))
            .put("skippedCredentials", skippedLogins)

        val zip = ZipOutputStream(NonClosingOutputStream(out))
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        zip.putJson(BackupPaths.MANIFEST, manifest.toString())
        zip.putJson(BackupPaths.FOLDERS, foldersJson(contents.folders))
        zip.putJson(BackupPaths.NOTES, notesJson(contents.notes))
        zip.putJson(BackupPaths.CREDENTIALS, credentialsJson(contents.credentials))
        zip.putJson(BackupPaths.FILES, filesJson(contents.files))
        zip.putJson(BackupPaths.SETTINGS, JSONObject(contents.settings as Map<*, *>).toString())

        // Photos, PDFs and archives rarely compress: favor speed for file contents.
        zip.setLevel(Deflater.BEST_SPEED)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        blobEntries.forEachIndexed { index, (path, file) ->
            checkCancelled()
            zip.putNextEntry(ZipEntry(path))
            var copied = 0L
            try {
                FileInputStream(file).use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        copied += read
                        checkCancelled()
                    }
                }
            } catch (e: IOException) {
                throw BackupException(BackupError.CANNOT_READ)
            }
            // The size was promised in the manifest; a file that changed meanwhile fails the backup.
            if (copied != sizes[index].second) throw BackupException(BackupError.CANNOT_READ)
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()

        return BackupSummary(
            notes = contents.notes.size,
            logins = contents.credentials.size,
            files = contents.files.size,
            folders = contents.folders.size,
            noteImages = images.size,
            missingFiles = missingFiles.size,
            missingNoteImages = missingImages.size,
            skippedLogins = skippedLogins
        )
    }

    private fun ZipOutputStream.putJson(path: String, json: String) {
        checkCancelled()
        putNextEntry(ZipEntry(path))
        write(json.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private class NonClosingOutputStream(out: OutputStream) : java.io.FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * A backup that has been decrypted, checked and unpacked into a private staging directory, but not
 * applied. [stagedFiles] and [stagedImages] hold the file contents and note images by id / name.
 */
class StagedBackup(
    val contents: BackupContents,
    val createdAt: Long,
    val stagedFiles: File,
    val stagedImages: File,
    val missingFiles: Set<String>,
    val missingNoteImages: Set<String>,
    val skippedLogins: Int
) {
    val noteImageCount: Int
        get() = stagedImages.listFiles()?.size ?: 0
}

class BackupArchiveReader(
    private val maxJsonBytes: Int = 64 * 1024 * 1024,
    private val maxBlobs: Int = 500_000,
    private val checkCancelled: () -> Unit = {}
) {

    /**
     * Reads the archive from [input] (a decrypting stream), unpacking file contents and images into
     * [stagingDir], which must be empty and private. [ensureSpace] is called with the total size of
     * all contents before anything is unpacked and should throw if there is not enough room.
     * Throws [BackupException] for anything invalid; the caller deletes [stagingDir] on failure.
     */
    fun read(input: InputStream, stagingDir: File, ensureSpace: (Long) -> Unit = {}): StagedBackup {
        val filesDir = File(stagingDir, "files")
        val imagesDir = File(stagingDir, "notes")
        if (!filesDir.mkdirs() || !imagesDir.mkdirs()) throw BackupException(BackupError.NOT_ENOUGH_STORAGE)

        val zip = ZipInputStream(input)
        try {
            val first = zip.nextEntry ?: throw BackupException(BackupError.INVALID_CONTENT)
            if (first.name != BackupPaths.MANIFEST || first.isDirectory) throw BackupException(BackupError.INVALID_CONTENT)
            val manifest = parseManifest(readJson(zip))

            val totalBytes = manifest.blobSizes.values.sum()
            ensureSpace(totalBytes)

            val json = HashMap<String, String>()
            val seenBlobs = HashSet<String>()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) throw BackupException(BackupError.INVALID_CONTENT)
                when {
                    name in BackupPaths.DATA_ENTRIES -> {
                        if (json.containsKey(name)) throw BackupException(BackupError.INVALID_CONTENT)
                        json[name] = readJson(zip)
                    }
                    name.startsWith(BackupPaths.FILE_BLOB_PREFIX) || name.startsWith(BackupPaths.NOTE_IMAGE_PREFIX) -> {
                        val declared = manifest.blobSizes[name] ?: throw BackupException(BackupError.INVALID_CONTENT)
                        if (!seenBlobs.add(name)) throw BackupException(BackupError.INVALID_CONTENT)
                        val target = stagingTargetFor(name, filesDir, imagesDir)
                        stage(zip, target, declared, buffer)
                    }
                    else -> throw BackupException(BackupError.INVALID_CONTENT)
                }
            }
            // Read to the very end so the last encrypted chunk (and "nothing after it") is verified too.
            while (input.read(buffer) >= 0) checkCancelled()
            if (BackupPaths.DATA_ENTRIES.any { it !in json }) throw BackupException(BackupError.INCOMPLETE)
            if (seenBlobs.size != manifest.blobSizes.size) throw BackupException(BackupError.INCOMPLETE)

            val contents = BackupContents(
                folders = parseFolders(json.getValue(BackupPaths.FOLDERS)),
                notes = parseNotes(json.getValue(BackupPaths.NOTES)),
                credentials = parseCredentials(json.getValue(BackupPaths.CREDENTIALS)),
                files = parseFiles(json.getValue(BackupPaths.FILES)),
                settings = parseSettings(json.getValue(BackupPaths.SETTINGS))
            )
            validate(contents, manifest, filesDir)
            return StagedBackup(
                contents = contents,
                createdAt = manifest.createdAt,
                stagedFiles = filesDir,
                stagedImages = imagesDir,
                missingFiles = manifest.missingFiles,
                missingNoteImages = manifest.missingNoteImages,
                skippedLogins = manifest.skippedCredentials
            )
        } catch (e: BackupException) {
            throw e
        } catch (e: ZipException) {
            throw BackupException(BackupError.DAMAGED)
        } catch (e: JSONException) {
            throw BackupException(BackupError.INVALID_CONTENT)
        } catch (e: IOException) {
            // Most likely the device ran out of space while unpacking.
            throw BackupException(if (stagingDir.usableSpace < LOW_SPACE_BYTES) BackupError.NOT_ENOUGH_STORAGE else BackupError.CANNOT_READ)
        }
    }

    // ---------- manifest ----------

    private class Manifest(
        val createdAt: Long,
        val blobSizes: Map<String, Long>,
        val missingFiles: Set<String>,
        val missingNoteImages: Set<String>,
        val skippedCredentials: Int
    )

    private fun parseManifest(text: String): Manifest {
        val json = JSONObject(text)
        if (json.optString("format") != BackupPaths.FORMAT_NAME) throw BackupException(BackupError.INVALID_CONTENT)
        if (json.optInt("version", -1) != BackupPaths.CONTENT_VERSION) throw BackupException(BackupError.UNSUPPORTED_VERSION)
        val blobs = json.getJSONArray("blobs")
        if (blobs.length() > maxBlobs) throw BackupException(BackupError.TOO_LARGE)
        val sizes = LinkedHashMap<String, Long>()
        for (i in 0 until blobs.length()) {
            val blob = blobs.getJSONObject(i)
            val path = blob.getString("path")
            val size = blob.getLong("size")
            if (size < 0 || !isValidBlobPath(path) || sizes.put(path, size) != null) {
                throw BackupException(BackupError.INVALID_CONTENT)
            }
        }
        return Manifest(
            createdAt = json.optLong("createdAt", 0L),
            blobSizes = sizes,
            missingFiles = json.optJSONArray("missingFiles").strings().toSet(),
            missingNoteImages = json.optJSONArray("missingNoteImages").strings().toSet(),
            skippedCredentials = json.optInt("skippedCredentials", 0).coerceAtLeast(0)
        )
    }

    private fun isValidBlobPath(path: String): Boolean = when {
        path.startsWith(BackupPaths.FILE_BLOB_PREFIX) ->
            BackupPaths.isSafeFileId(path.removePrefix(BackupPaths.FILE_BLOB_PREFIX))
        path.startsWith(BackupPaths.NOTE_IMAGE_PREFIX) ->
            NoteImageStore.isValidName(path.removePrefix(BackupPaths.NOTE_IMAGE_PREFIX))
        else -> false
    }

    /** The staging file for a validated blob path. The name is checked again, never used raw. */
    private fun stagingTargetFor(path: String, filesDir: File, imagesDir: File): File {
        if (!isValidBlobPath(path)) throw BackupException(BackupError.INVALID_CONTENT)
        return if (path.startsWith(BackupPaths.FILE_BLOB_PREFIX)) {
            File(filesDir, path.removePrefix(BackupPaths.FILE_BLOB_PREFIX))
        } else {
            File(imagesDir, path.removePrefix(BackupPaths.NOTE_IMAGE_PREFIX))
        }
    }

    // ---------- entries ----------

    private fun readJson(zip: ZipInputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxJsonBytes) throw BackupException(BackupError.TOO_LARGE)
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Unpacks exactly [declared] bytes into [target]; more or fewer is an error. */
    private fun stage(zip: ZipInputStream, target: File, declared: Long, buffer: ByteArray) {
        var total = 0L
        FileOutputStream(target).use { output ->
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                total += read
                if (total > declared) throw BackupException(BackupError.INVALID_CONTENT)
                output.write(buffer, 0, read)
                checkCancelled()
            }
            output.fd.sync()
        }
        if (total != declared) throw BackupException(BackupError.INCOMPLETE)
    }

    // ---------- records ----------

    private fun parseFolders(text: String): List<FolderRecord> = JSONArray(text).objects().map {
        FolderRecord(
            id = it.getString("id"),
            module = it.getString("module"),
            name = it.getString("name"),
            parentId = it.optStringOrNull("parentId"),
            createdAt = it.getLong("createdAt")
        )
    }

    private fun parseNotes(text: String): List<NoteRecord> = JSONArray(text).objects().map {
        NoteRecord(
            id = it.getString("id"),
            folderId = it.optStringOrNull("folderId"),
            title = it.getString("title"),
            description = it.getString("description"),
            body = it.getString("body"),
            createdAt = it.getLong("createdAt"),
            updatedAt = it.getLong("updatedAt"),
            isFavorite = it.getBoolean("isFavorite"),
            isPinned = it.getBoolean("isPinned"),
            colorIndex = it.getInt("colorIndex")
        )
    }

    private fun parseCredentials(text: String): List<CredentialRecord> = JSONArray(text).objects().map {
        CredentialRecord(
            id = it.getString("id"),
            folderId = it.optStringOrNull("folderId"),
            serviceName = it.getString("serviceName"),
            websiteUrl = it.getString("websiteUrl"),
            username = it.getString("username"),
            password = it.getString("password"),
            notes = it.getString("notes"),
            isFavorite = it.getBoolean("isFavorite"),
            createdAt = it.getLong("createdAt"),
            updatedAt = it.getLong("updatedAt")
        )
    }

    private fun parseFiles(text: String): List<FileRecord> = JSONArray(text).objects().map {
        FileRecord(
            id = it.getString("id"),
            folderId = it.optStringOrNull("folderId"),
            originalName = it.getString("originalName"),
            displayName = it.getString("displayName"),
            mimeType = it.getString("mimeType"),
            sizeBytes = it.getLong("sizeBytes"),
            keywords = it.getString("keywords"),
            description = it.getString("description"),
            isFavorite = it.getBoolean("isFavorite"),
            createdAt = it.getLong("createdAt"),
            updatedAt = it.getLong("updatedAt")
        )
    }

    private fun parseSettings(text: String): Map<String, String> {
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { json.get(it).toString() }
    }

    /** Cross-checks the records before anything is applied. */
    private fun validate(contents: BackupContents, manifest: Manifest, filesDir: File) {
        fun fail(): Nothing = throw BackupException(BackupError.INVALID_CONTENT)

        val folderById = HashMap<String, FolderRecord>()
        contents.folders.forEach { folder ->
            if (folder.id.isBlank() || folder.id.length > MAX_ID_LENGTH || folder.module !in FOLDER_MODULES) fail()
            if (folderById.put(folder.id, folder) != null) fail()
        }
        contents.folders.forEach { folder ->
            // Parents must exist in the same module, and following parents must never loop.
            var parentId = folder.parentId
            var steps = 0
            while (parentId != null) {
                val parent = folderById[parentId] ?: fail()
                if (parent.module != folder.module || ++steps > contents.folders.size) fail()
                parentId = parent.parentId
            }
        }
        fun checkFolder(folderId: String?, module: String) {
            if (folderId != null && folderById[folderId]?.module != module) fail()
        }
        fun checkIds(ids: List<String>) {
            if (ids.any { it.isBlank() || it.length > MAX_ID_LENGTH } || ids.toSet().size != ids.size) fail()
        }

        checkIds(contents.notes.map { it.id })
        contents.notes.forEach { checkFolder(it.folderId, "NOTES") }
        checkIds(contents.credentials.map { it.id })
        contents.credentials.forEach { checkFolder(it.folderId, "PASSWORDS") }
        checkIds(contents.files.map { it.id })
        contents.files.forEach { file ->
            if (!BackupPaths.isSafeFileId(file.id)) fail()
            checkFolder(file.folderId, "FILES")
            val hasBlob = File(filesDir, file.id).isFile
            // Every file either comes with its contents or was recorded as missing at backup time.
            if (!hasBlob && file.id !in manifest.missingFiles) throw BackupException(BackupError.INCOMPLETE)
        }
        // Contents with no file record would be orphans.
        val fileIds = contents.files.mapTo(HashSet()) { it.id }
        filesDir.listFiles()?.forEach { if (it.name !in fileIds) fail() }
    }

    private companion object {
        const val MAX_ID_LENGTH = 128
        const val LOW_SPACE_BYTES = 20L * 1024 * 1024
        val FOLDER_MODULES = setOf("NOTES", "FILES", "PASSWORDS")
    }
}

// ---------- JSON helpers ----------

private fun foldersJson(folders: List<FolderRecord>): String = JSONArray().also { array ->
    folders.forEach {
        array.put(
            JSONObject()
                .put("id", it.id)
                .put("module", it.module)
                .put("name", it.name)
                .put("parentId", it.parentId ?: JSONObject.NULL)
                .put("createdAt", it.createdAt)
        )
    }
}.toString()

private fun notesJson(notes: List<NoteRecord>): String = JSONArray().also { array ->
    notes.forEach {
        array.put(
            JSONObject()
                .put("id", it.id)
                .put("folderId", it.folderId ?: JSONObject.NULL)
                .put("title", it.title)
                .put("description", it.description)
                .put("body", it.body)
                .put("createdAt", it.createdAt)
                .put("updatedAt", it.updatedAt)
                .put("isFavorite", it.isFavorite)
                .put("isPinned", it.isPinned)
                .put("colorIndex", it.colorIndex)
        )
    }
}.toString()

private fun credentialsJson(credentials: List<CredentialRecord>): String = JSONArray().also { array ->
    credentials.forEach {
        array.put(
            JSONObject()
                .put("id", it.id)
                .put("folderId", it.folderId ?: JSONObject.NULL)
                .put("serviceName", it.serviceName)
                .put("websiteUrl", it.websiteUrl)
                .put("username", it.username)
                .put("password", it.password)
                .put("notes", it.notes)
                .put("isFavorite", it.isFavorite)
                .put("createdAt", it.createdAt)
                .put("updatedAt", it.updatedAt)
        )
    }
}.toString()

private fun filesJson(files: List<FileRecord>): String = JSONArray().also { array ->
    files.forEach {
        array.put(
            JSONObject()
                .put("id", it.id)
                .put("folderId", it.folderId ?: JSONObject.NULL)
                .put("originalName", it.originalName)
                .put("displayName", it.displayName)
                .put("mimeType", it.mimeType)
                .put("sizeBytes", it.sizeBytes)
                .put("keywords", it.keywords)
                .put("description", it.description)
                .put("isFavorite", it.isFavorite)
                .put("createdAt", it.createdAt)
                .put("updatedAt", it.updatedAt)
        )
    }
}.toString()

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
