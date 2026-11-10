package com.zero7goat.taz.core.storage

import java.util.Locale

private const val MAX_NAME_LENGTH = 200

/**
 * Makes an externally supplied file name safe to show and to use as a plain file name.
 * Path parts are dropped, control characters become "_", and the length is limited
 * (keeping the extension). An unusable name becomes "file".
 *
 * Stored files never use this name on disk (they use a generated id), so it only affects
 * what is displayed and the name given to other apps when sharing.
 */
fun sanitizeFileName(raw: String?): String {
    var name = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    name = name.map { c -> if (c.code < 0x20 || c.code == 0x7F) '_' else c }.joinToString("").trim()
    if (name.isEmpty() || name == "." || name == "..") return "file"
    if (name.length > MAX_NAME_LENGTH) {
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        name = if (ext.isNotEmpty() && ext.length < 20) {
            name.take(MAX_NAME_LENGTH - ext.length).trimEnd() + ext
        } else {
            name.take(MAX_NAME_LENGTH)
        }
    }
    return name
}

/** The lowercase extension without the dot, or an empty string. ".hidden" has no extension. */
fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
}

/**
 * Returns [desired] if no name in [existing] equals it (ignoring case); otherwise adds
 * " (2)", " (3)" and so on before the extension.
 */
fun uniqueName(desired: String, existing: Collection<String>): String {
    val taken = existing.map { it.lowercase(Locale.ROOT) }.toSet()
    if (desired.lowercase(Locale.ROOT) !in taken) return desired

    val dot = desired.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < desired.length - 1
    val base = if (hasExtension) desired.substring(0, dot) else desired
    val extension = if (hasExtension) desired.substring(dot) else ""
    var counter = 2
    while (true) {
        val candidate = "$base ($counter)$extension"
        if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
        counter++
    }
}
