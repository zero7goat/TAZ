package com.zero7goat.taz.features.files.domain

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FileRulesTest {

    @Test
    fun categorizesByMimeType() {
        assertEquals(FileCategory.IMAGE, fileCategory("image/png", "x"))
        assertEquals(FileCategory.VIDEO, fileCategory("video/mp4", "x"))
        assertEquals(FileCategory.AUDIO, fileCategory("audio/mpeg", "x"))
        assertEquals(FileCategory.DOCUMENT, fileCategory("application/pdf", "x"))
        assertEquals(FileCategory.DOCUMENT, fileCategory("text/plain", "x"))
        assertEquals(FileCategory.DOCUMENT, fileCategory("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "x"))
        assertEquals(FileCategory.DOCUMENT, fileCategory("application/epub+zip", "x"))
        assertEquals(FileCategory.ARCHIVE, fileCategory("application/zip", "x"))
    }

    @Test
    fun fallsBackToTheExtension() {
        assertEquals(FileCategory.IMAGE, fileCategory("application/octet-stream", "photo.JPG"))
        assertEquals(FileCategory.ARCHIVE, fileCategory("application/octet-stream", "backup.7z"))
        assertEquals(FileCategory.OTHER, fileCategory("application/octet-stream", "data.bin"))
        assertEquals(FileCategory.OTHER, fileCategory("", "noext"))
    }

    @Test
    fun choosesAPreviewKind() {
        assertEquals(PreviewKind.PDF, previewKind("application/pdf", "a"))
        assertEquals(PreviewKind.PDF, previewKind("application/octet-stream", "scan.pdf"))
        assertEquals(PreviewKind.IMAGE, previewKind("image/jpeg", "a"))
        assertEquals(PreviewKind.IMAGE, previewKind("application/octet-stream", "a.webp"))
        assertEquals(PreviewKind.TEXT, previewKind("text/plain", "a"))
        assertEquals(PreviewKind.TEXT, previewKind("application/json", "a"))
        assertEquals(PreviewKind.TEXT, previewKind("application/octet-stream", "notes.md"))
        assertEquals(PreviewKind.NONE, previewKind("image/svg+xml", "logo.svg"))
        assertEquals(PreviewKind.NONE, previewKind("video/mp4", "a.mp4"))
        assertEquals(PreviewKind.NONE, previewKind("application/zip", "a.zip"))
        assertEquals(PreviewKind.NONE, previewKind("application/octet-stream", "a.bin"))
    }

    @Test
    fun normalizesKeywords() {
        assertEquals("work,invoice,2024", normalizeKeywords("  Work, INVOICE ;work\n2024 ,, "))
        assertEquals("", normalizeKeywords("  , ;  "))
        assertEquals("two words", normalizeKeywords("Two    Words"))
    }

    @Test
    fun limitsKeywords() {
        val many = (1..30).joinToString(",") { "k$it" }
        assertEquals(20, keywordList(normalizeKeywords(many)).size)
        assertEquals(40, keywordList(normalizeKeywords("x".repeat(100))).single().length)
    }

    @Test
    fun readsKeywordsBack() {
        assertEquals(listOf("a", "b"), keywordList("a,b"))
        assertEquals(emptyList<String>(), keywordList(""))
    }

    @Test
    fun formatsSizes() {
        assertEquals("0 B", formatFileSize(0, Locale.US))
        assertEquals("1023 B", formatFileSize(1023, Locale.US))
        assertEquals("1.0 KB", formatFileSize(1024, Locale.US))
        assertEquals("1.5 KB", formatFileSize(1536, Locale.US))
        assertEquals("5.0 MB", formatFileSize(5L * 1024 * 1024, Locale.US))
        assertEquals("1.0 GB", formatFileSize(1024L * 1024 * 1024, Locale.US))
    }
}
