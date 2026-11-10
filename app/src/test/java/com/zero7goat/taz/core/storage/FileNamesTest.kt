package com.zero7goat.taz.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun dropsAnyPathPart() {
        assertEquals("c.txt", sanitizeFileName("a/b/c.txt"))
        assertEquals("evil.exe", sanitizeFileName("..\\..\\evil.exe"))
        assertEquals("passwd", sanitizeFileName("/etc/passwd"))
    }

    @Test
    fun replacesControlCharactersAndTrims() {
        assertEquals("a_b", sanitizeFileName("a\u0000b"))
        assertEquals("a_b.txt", sanitizeFileName("a\nb.txt"))
        assertEquals("report .pdf", sanitizeFileName("  report .pdf  "))
    }

    @Test
    fun unusableNamesBecomeFile() {
        assertEquals("file", sanitizeFileName(null))
        assertEquals("file", sanitizeFileName(""))
        assertEquals("file", sanitizeFileName("   "))
        assertEquals("file", sanitizeFileName("."))
        assertEquals("file", sanitizeFileName(".."))
        assertEquals("file", sanitizeFileName("a/.."))
    }

    @Test
    fun keepsHiddenFileNames() {
        assertEquals(".hidden", sanitizeFileName(".hidden"))
    }

    @Test
    fun limitsLengthButKeepsTheExtension() {
        val name = sanitizeFileName("x".repeat(300) + ".pdf")
        assertTrue(name.length <= 200)
        assertTrue(name.endsWith(".pdf"))
        assertEquals(200, sanitizeFileName("y".repeat(500)).length)
    }

    @Test
    fun findsTheExtension() {
        assertEquals("txt", extensionOf("a.TXT"))
        assertEquals("gz", extensionOf("archive.tar.gz"))
        assertEquals("", extensionOf(".hidden"))
        assertEquals("", extensionOf("noext"))
        assertEquals("", extensionOf("trailing."))
    }

    @Test
    fun keepsANameThatIsFree() {
        assertEquals("photo.jpg", uniqueName("photo.jpg", emptyList()))
        assertEquals("photo.jpg", uniqueName("photo.jpg", listOf("other.jpg")))
    }

    @Test
    fun addsACounterBeforeTheExtension() {
        assertEquals("photo (2).jpg", uniqueName("photo.jpg", listOf("photo.jpg")))
        assertEquals("photo (2).jpg", uniqueName("photo.jpg", listOf("PHOTO.JPG")))
        assertEquals("a (3).txt", uniqueName("a.txt", listOf("a.txt", "a (2).txt")))
        assertEquals("notes (2)", uniqueName("notes", listOf("notes")))
        assertEquals(".bashrc (2)", uniqueName(".bashrc", listOf(".bashrc")))
    }
}
