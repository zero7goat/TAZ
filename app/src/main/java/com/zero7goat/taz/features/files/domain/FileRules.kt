package com.zero7goat.taz.features.files.domain

import com.zero7goat.taz.core.storage.extensionOf
import java.util.Locale

enum class FileCategory(val label: String) {
    IMAGE("Images"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    DOCUMENT("Documents"),
    ARCHIVE("Archives"),
    OTHER("Other")
}

/** What the file screen can show inline. Everything else offers details and Open. */
enum class PreviewKind { IMAGE, TEXT, PDF, NONE }

private val ImageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
private val VideoExtensions = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")
private val AudioExtensions = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus")
private val DocumentExtensions = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "md", "csv", "epub"
)
private val ArchiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
private val TextExtensions = setOf(
    "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log", "ini", "cfg", "conf",
    "properties", "kt", "java", "js", "ts", "py", "c", "h", "cpp", "sh", "bat", "html", "css", "sql"
)
private val RasterImageExtensions = ImageExtensions - "svg"
private val DocumentMimeHints = listOf("msword", "officedocument", "ms-excel", "ms-powerpoint", "opendocument", "rtf", "epub")
private val ArchiveMimeHints = listOf("zip", "rar", "7z", "tar", "gzip", "compressed")
private val TextMimeTypes = setOf("application/json", "application/xml", "application/x-sh", "application/javascript")

/** Groups a file for filtering and sorting, from its MIME type with the extension as a fallback. */
fun fileCategory(mimeType: String, name: String): FileCategory {
    val mime = mimeType.lowercase(Locale.ROOT)
    val ext = extensionOf(name)
    return when {
        mime.startsWith("image/") -> FileCategory.IMAGE
        mime.startsWith("video/") -> FileCategory.VIDEO
        mime.startsWith("audio/") -> FileCategory.AUDIO
        mime.startsWith("text/") || mime == "application/pdf" ||
            DocumentMimeHints.any { mime.contains(it) } -> FileCategory.DOCUMENT
        ArchiveMimeHints.any { mime.contains(it) } -> FileCategory.ARCHIVE
        ext in ImageExtensions -> FileCategory.IMAGE
        ext in VideoExtensions -> FileCategory.VIDEO
        ext in AudioExtensions -> FileCategory.AUDIO
        ext in DocumentExtensions -> FileCategory.DOCUMENT
        ext in ArchiveExtensions -> FileCategory.ARCHIVE
        else -> FileCategory.OTHER
    }
}

/** Decides whether (and how) a file can be previewed inside the app. */
fun previewKind(mimeType: String, name: String): PreviewKind {
    val mime = mimeType.lowercase(Locale.ROOT)
    val ext = extensionOf(name)
    return when {
        mime == "application/pdf" || ext == "pdf" -> PreviewKind.PDF
        mime == "image/svg+xml" -> PreviewKind.NONE
        mime.startsWith("image/") -> PreviewKind.IMAGE
        mime.startsWith("text/") || mime in TextMimeTypes -> PreviewKind.TEXT
        ext in RasterImageExtensions -> PreviewKind.IMAGE
        ext in TextExtensions -> PreviewKind.TEXT
        else -> PreviewKind.NONE
    }
}

private const val MAX_KEYWORDS = 20
private const val MAX_KEYWORD_LENGTH = 40

/**
 * Turns typed keywords into the stored form: lowercase, trimmed, no duplicates, separated by
 * commas. Commas, semicolons and line breaks all separate keywords.
 */
fun normalizeKeywords(input: String): String =
    input.split(',', ';', '\n', '\r')
        .map { it.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT).take(MAX_KEYWORD_LENGTH) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_KEYWORDS)
        .joinToString(",")

/** The keywords in their stored form as a list. */
fun keywordList(stored: String): List<String> =
    stored.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** "812 B", "1.5 KB", "2.4 MB" and so on. */
fun formatFileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(locale, "%.1f %s", value, units[unit])
}
