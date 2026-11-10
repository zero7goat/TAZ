package com.zero7goat.taz.features.notes.data

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteImageStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: NoteImageStore

    @Before
    fun setUp() {
        store = NoteImageStore(temp.root)
    }

    private fun import(bytes: ByteArray = byteArrayOf(1, 2, 3), ext: String = "png") =
        store.importStream(ByteArrayInputStream(bytes), ext)

    @Test
    fun importedImagesGetGeneratedNamesInsideTheStore() {
        val name = import()
        assertTrue(NoteImageStore.isValidName(name))
        val file = store.resolve(name)!!
        assertEquals(File(temp.root, "vault/note_images").canonicalPath, file.parentFile!!.canonicalPath)
        assertEquals(3L, file.length())
    }

    @Test
    fun unsafeNamesNeverResolve() {
        listOf("../x.png", "a/b.png", "x.png", "..", "", "0f8fad5b-d9cb-469f-a165-70867728950e.exe",
            "0f8fad5b-d9cb-469f-a165-70867728950e.png/../../x").forEach {
            assertNull(it, store.resolve(it))
        }
    }

    @Test
    fun tooLargeImagesAreRejectedAndLeaveNothingBehind() {
        try {
            store.importStream(ByteArrayInputStream(ByteArray(100)), "jpg", maxBytes = 10)
            fail("Expected ImageTooLargeException")
        } catch (e: NoteImageStore.ImageTooLargeException) {
            // expected
        }
        assertTrue(store.directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun referencesAreFoundOnlyForValidNames() {
        val name = import()
        val body = "text\n${NoteImageStore.markdownFor(name)}\n![x](note-image:../../etc/passwd)\n![y](https://e.com/a.png)"
        assertEquals(setOf(name), NoteImageStore.referencedImageNames(body))
        assertEquals(name, NoteImageStore.imageNameInLine("  ${NoteImageStore.markdownFor(name)} "))
        assertNull(NoteImageStore.imageNameInLine("before ${NoteImageStore.markdownFor(name)}"))
    }

    @Test
    fun cleanupKeepsReferencedAndRecentImages() {
        val kept = import()
        val orphan = import()
        val recentOrphan = import()
        val now = System.currentTimeMillis()
        store.resolve(kept)!!.setLastModified(now - 3 * DAY)
        store.resolve(orphan)!!.setLastModified(now - 3 * DAY)

        val removed = store.deleteUnreferenced(listOf("note ${NoteImageStore.markdownFor(kept)}"), olderThanMs = DAY, nowMs = now)

        assertEquals(1, removed)
        assertTrue(store.resolve(kept)!!.exists())
        assertFalse(store.resolve(orphan)!!.exists())
        assertTrue(store.resolve(recentOrphan)!!.exists())
    }

    @Test
    fun mimeTypesMapToSupportedExtensions() {
        assertEquals("jpg", NoteImageStore.extensionForMime("image/jpeg"))
        assertEquals("webp", NoteImageStore.extensionForMime("IMAGE/WEBP"))
        assertNull(NoteImageStore.extensionForMime("image/svg+xml"))
        assertNull(NoteImageStore.extensionForMime(null))
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
