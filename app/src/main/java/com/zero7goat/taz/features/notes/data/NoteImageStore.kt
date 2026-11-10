package com.zero7goat.taz.features.notes.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Private storage for images inserted into notes. Pure file logic (no Android classes) so it can be
 * unit tested.
 *
 * A note refers to an image by a line of the form `![image](note-image:<name>)` in its body. The
 * name is always generated here (a random UUID plus an image extension), never taken from the
 * user's file, and every name read back from a note or a backup is checked against [NAME_PATTERN]
 * before it is used, so a reference can never point outside [directory].
 *
 * Images are stored as copies in the app's private storage; like stored files, they are not
 * encrypted. Layout: `<baseDir>/vault/note_images/<uuid>.<ext>`.
 */
class NoteImageStore(baseDir: File) {

    val directory = File(baseDir, RELATIVE_DIR)

    /**
     * Copies [input] into the store and returns the new image name. Throws [IOException] if the
     * copy fails or is larger than [maxBytes]; the partial copy is removed.
     */
    @Throws(IOException::class)
    fun importStream(input: InputStream, extension: String, maxBytes: Long = MAX_IMAGE_BYTES): String {
        val ext = extension.lowercase()
        require(ext in EXTENSIONS) { "Unsupported image type" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create image directory")
        val name = "${UUID.randomUUID()}.$ext"
        val temp = File(directory, "$name.part")
        try {
            var total = 0L
            FileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw ImageTooLargeException()
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            if (total == 0L) throw IOException("Empty image")
            if (!temp.renameTo(File(directory, name))) throw IOException("Cannot store image")
            return name
        } finally {
            temp.delete()
        }
    }

    /** The stored file for [name], or null if the name is not a valid image name. It may not exist. */
    fun resolve(name: String): File? = if (isValidName(name)) File(directory, name) else null

    /** Names of every stored image. */
    fun storedNames(): Set<String> =
        directory.listFiles()?.filter { it.isFile && isValidName(it.name) }?.map { it.name }?.toSet().orEmpty()

    /** Deletes the images named in [names]. Invalid names are ignored. */
    fun delete(names: Collection<String>) {
        names.forEach { name -> resolve(name)?.delete() }
    }

    /**
     * Deletes stored images not referenced by any of [bodies] and last modified at least
     * [olderThanMs] before [nowMs] (so an image inserted into a note that is still being edited is
     * kept). Leftover partial copies are removed too. Returns how many images were removed.
     */
    fun deleteUnreferenced(
        bodies: Collection<String>,
        olderThanMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        val referenced = bodies.flatMapTo(HashSet()) { referencedImageNames(it) }
        var removed = 0
        directory.listFiles()?.forEach { file ->
            val old = file.lastModified() <= nowMs - olderThanMs
            when {
                !file.isFile || !old -> Unit
                file.name.endsWith(".part") -> file.delete()
                isValidName(file.name) && file.name !in referenced && file.delete() -> removed++
            }
        }
        return removed
    }

    class ImageTooLargeException : IOException("Image too large")

    companion object {
        /** Relative to the app's files directory. */
        const val RELATIVE_DIR = "vault/note_images"
        const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
        const val URI_SCHEME = "note-image:"

        val EXTENSIONS = setOf("jpg", "png", "webp", "gif", "heic")

        private val NAME_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp|gif|heic)$")

        private val REFERENCE_PATTERN =
            Regex("!\\[[^\\]\\n]*\\]\\(" + Regex.escape(URI_SCHEME) + "([^)\\s]+)\\)")

        fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)

        /** The extension to store an image of [mimeType] under, or null if it is not supported. */
        fun extensionForMime(mimeType: String?): String? = when (mimeType?.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            else -> null
        }

        /** The Markdown line that shows [name] in a note. */
        fun markdownFor(name: String): String = "![image]($URI_SCHEME$name)"

        /** Valid image names referenced in [body]. */
        fun referencedImageNames(body: String): Set<String> =
            REFERENCE_PATTERN.findAll(body).map { it.groupValues[1] }.filter { isValidName(it) }.toSet()

        /** If [line] is exactly one image reference, the image name; otherwise null. */
        fun imageNameInLine(line: String): String? {
            val match = REFERENCE_PATTERN.matchEntire(line.trim()) ?: return null
            return match.groupValues[1].takeIf { isValidName(it) }
        }
    }
}
