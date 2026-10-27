package com.zero7goat.tung.core.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Prepares a stored file for other apps. A temporary copy with the file's real name is placed
 * in cache/share and exposed through FileProvider as a content:// URI. The vault's own path and
 * generated file names are never shared. Copying streams the file, so large files are fine.
 *
 * Call from a background thread.
 */
object ShareStaging {

    private const val SHARE_DIR = "share"

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** Copies [source] under [displayName] and returns a content URI for it. Older staged copies are removed first. */
    fun stage(context: Context, source: File, displayName: String): Uri {
        clear(context)
        val directory = File(File(context.cacheDir, SHARE_DIR), UUID.randomUUID().toString())
        check(directory.mkdirs()) { "Cannot create share directory" }
        val target = File(directory, sanitizeFileName(displayName))
        source.copyTo(target, overwrite = true)
        return FileProvider.getUriForFile(context, authority(context), target)
    }

    /** Deletes every staged copy. */
    fun clear(context: Context) {
        File(context.cacheDir, SHARE_DIR).deleteRecursively()
    }
}
