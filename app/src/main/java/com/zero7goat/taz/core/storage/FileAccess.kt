package com.zero7goat.taz.core.storage

import android.content.Context
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reading stored files now that they are encrypted. Viewers and "open with" need an ordinary
 * file, so an encrypted copy is decrypted into TAZ's private cache (cache/view, which no other
 * app can read) while it is being looked at. Those copies are wiped when TAZ locks and every time
 * TAZ starts.
 */
object FileAccess {

    private const val VIEW_DIR = "view"

    fun cipher() = VaultFileCipher(KeystoreFieldCipher())

    /** A readable copy of [raw] (the stored file of [fileId]): itself if plain, else a decrypted copy. */
    fun readable(context: Context, raw: File, fileId: String, cipher: VaultFileCipher): File {
        if (!cipher.isEncrypted(raw)) return raw
        val dir = File(context.cacheDir, VIEW_DIR).also { it.mkdirs() }
        val target = File(dir, fileId)
        if (target.isFile && target.lastModified() >= raw.lastModified()) return target
        val partial = File(dir, "$fileId.part")
        try {
            cipher.open(raw, fileId).use { input -> FileOutputStream(partial).use { input.copyTo(it, 64 * 1024) } }
            if (!partial.renameTo(target)) throw VaultFileException("Cannot prepare file")
        } finally {
            partial.delete()
        }
        return target
    }

    /** Deletes every decrypted viewing copy. */
    fun clearViewCache(context: Context) {
        File(context.cacheDir, VIEW_DIR).deleteRecursively()
    }
}

/**
 * Encrypts the stored copies of files saved before TAZ 2.0, one at a time, in the background.
 * Each file is encrypted into a temporary file, checked by decrypting it again and comparing a
 * SHA-256 of the contents, and only then put in place of the plain copy. Stopping at any moment
 * is safe: readers recognise encrypted files by their header, and an unfinished temporary file is
 * simply discarded.
 */
object VaultFileMigrator {

    /** Returns how many files are still plain afterwards (0 when everything is encrypted). */
    suspend fun run(context: Context): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = TazDatabase.getInstance(app).fileDao()
        val store = VaultFileStore(VaultPaths.root(app))
        val cipher = FileAccess.cipher()
        var left = 0
        dao.unencrypted().forEach { file ->
            val raw = store.resolve(file.storedPath)
            if (raw == null || !raw.isFile) return@forEach // missing copy: nothing to protect
            if (cipher.isEncrypted(raw)) {
                dao.setEncryption(file.id, 1)
                return@forEach
            }
            val temp = store.newTempFile()
            try {
                val before = sha256(raw.inputStream())
                FileOutputStream(temp).use { out ->
                    raw.inputStream().use { input -> cipher.encrypt(input, out, file.id) }
                    out.flush()
                    out.fd.sync()
                }
                val after = cipher.open(temp, file.id).use { sha256(it) }
                if (!before.contentEquals(after)) throw VaultFileException("Check failed")
                // Same directory tree, so this is a rename that replaces the plain copy in one step.
                if (!temp.renameTo(raw)) throw VaultFileException("Cannot replace file")
                dao.setEncryption(file.id, 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                left++ // kept as it was; tried again next start
            } finally {
                store.discardTemp(temp)
            }
        }
        left
    }

    private fun sha256(input: java.io.InputStream): ByteArray = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest()
    }
}
