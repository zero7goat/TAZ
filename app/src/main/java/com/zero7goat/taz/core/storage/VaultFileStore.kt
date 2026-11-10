package com.zero7goat.taz.core.storage

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * TAZ's private file storage. Pure file logic (no Android classes) so it can be unit tested.
 *
 * Layout under [baseDir] (the app's private files directory):
 *
 *     vault/files/<id>      stored copies; the name is a generated id, never a user file name
 *     vault/tmp/<n>.part    imports in progress
 *
 * Every path that comes from the database is checked with [resolve] before it is used, so a
 * damaged or hostile value can never point outside vault/files.
 */
class VaultFileStore(private val baseDir: File) {

    private val blobDir = File(baseDir, "$VAULT_DIR/$BLOB_DIR")
    private val tempDir = File(baseDir, "$VAULT_DIR/$TEMP_DIR")

    // Temp files created by this process that are still being written.
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Free space on the storage that holds the vault. */
    fun usableSpace(): Long = baseDir.usableSpace

    /** A new, empty temporary file for an import. Call [discardTemp] or [commit] when done. */
    @Throws(IOException::class)
    fun newTempFile(): File {
        if (!tempDir.isDirectory && !tempDir.mkdirs()) throw IOException("Cannot create temp directory")
        val file = File(tempDir, "${UUID.randomUUID()}.part")
        inFlight.add(file.name)
        return file
    }

    /** Deletes a temporary file (if it still exists) and forgets it. Safe to call after [commit]. */
    fun discardTemp(temp: File) {
        temp.delete()
        inFlight.remove(temp.name)
    }

    /**
     * Moves a finished temporary file into the vault under [id] and returns its relative path.
     * [id] may only contain letters, digits and dashes.
     */
    @Throws(IOException::class)
    fun commit(temp: File, id: String): String {
        require(SAFE_ID.matches(id)) { "Unsafe id" }
        if (!blobDir.isDirectory && !blobDir.mkdirs()) throw IOException("Cannot create storage directory")
        val destination = File(blobDir, id)
        if (destination.exists()) throw IOException("Destination exists")
        if (!temp.renameTo(destination)) throw IOException("Cannot move file into storage")
        inFlight.remove(temp.name)
        return relativePathFor(id)
    }

    /** The relative path (from [baseDir]) a stored copy with [id] lives at. */
    fun relativePathFor(id: String): String = "$VAULT_DIR/$BLOB_DIR/$id"

    /**
     * Resolves a stored relative path to a file, or returns null if it would point anywhere
     * other than directly inside the vault's file directory (path traversal, absolute paths,
     * symbolic links out of the directory, or nonsense). The file may or may not exist.
     */
    fun resolve(relativePath: String): File? {
        if (relativePath.isBlank() || relativePath.indexOf('\u0000') >= 0) return null
        return try {
            val allowed = blobDir.canonicalFile
            val candidate = File(baseDir, relativePath).canonicalFile
            if (candidate.parentFile == allowed) candidate else null
        } catch (e: IOException) {
            null
        }
    }

    /** Deletes a stored copy. Returns true if it is gone afterwards, false if the path is invalid or deletion failed. */
    fun delete(relativePath: String): Boolean {
        val file = resolve(relativePath) ?: return false
        return !file.exists() || file.delete()
    }

    /** Removes leftover temporary files from imports that were interrupted. Skips imports running now. */
    fun cleanTemp(): Int {
        var removed = 0
        tempDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in inFlight && file.delete()) removed++
        }
        return removed
    }

    /**
     * Deletes stored copies whose relative path is not in [known] and that were last modified at
     * least [olderThanMs] before [nowMs] (so a copy that was just stored is never touched).
     * Returns how many were removed.
     */
    fun deleteBlobsNotIn(
        known: Set<String>,
        olderThanMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        var removed = 0
        blobDir.listFiles()?.forEach { file ->
            val old = file.lastModified() <= nowMs - olderThanMs
            if (file.isFile && old && relativePathFor(file.name) !in known && file.delete()) removed++
        }
        return removed
    }

    private companion object {
        const val VAULT_DIR = "vault"
        const val BLOB_DIR = "files"
        const val TEMP_DIR = "tmp"
        val SAFE_ID = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}
