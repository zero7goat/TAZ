package com.zero7goat.taz.core.storage

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultFileStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var base: File
    private lateinit var store: VaultFileStore

    @Before
    fun setUp() {
        base = folder.newFolder("app")
        store = VaultFileStore(base)
    }

    private fun storeFile(id: String, content: String = "data"): String {
        val temp = store.newTempFile()
        temp.writeText(content)
        return store.commit(temp, id)
    }

    @Test
    fun commitMovesTheTempFileIntoTheVault() {
        val temp = store.newTempFile()
        temp.writeText("hello")
        val path = store.commit(temp, "abc-123")

        assertEquals("vault/files/abc-123", path)
        assertFalse(temp.exists())
        assertEquals("hello", store.resolve(path)!!.readText())
    }

    @Test
    fun storedNamesAreGeneratedIdsNotUserNames() {
        val path = storeFile("0f8c9a52-aaaa-bbbb-cccc-123456789abc")
        assertEquals("0f8c9a52-aaaa-bbbb-cccc-123456789abc", store.resolve(path)!!.name)
    }

    @Test
    fun resolveRejectsEveryPathOutsideTheVaultFileDirectory() {
        storeFile("ok")
        folder.newFile("outside.txt")
        val rejected = listOf(
            "../outside.txt",
            "vault/files/../../outside.txt",
            "vault/files/../tmp/x.part",
            "/etc/passwd",
            "",
            "   ",
            "vault/files/a\u0000b",
            "vault/files/sub/x",
            "vault/other/x",
            "vault/files",
            "vault"
        )
        rejected.forEach { assertNull("should reject: $it", store.resolve(it)) }
    }

    @Test
    fun resolveAcceptsAnEquivalentPathThatStaysInside() {
        val path = storeFile("ok")
        assertNotNull(store.resolve("vault/files/../files/ok"))
        assertEquals(store.resolve(path), store.resolve("vault/files/../files/ok"))
    }

    @Test
    fun deleteRemovesAStoredFile() {
        val path = storeFile("gone")
        assertTrue(store.delete(path))
        assertFalse(File(base, path).exists())
        assertTrue("deleting twice is fine", store.delete(path))
    }

    @Test
    fun deleteRefusesToTouchFilesOutsideTheVault() {
        val outside = folder.newFile("outside.txt")
        assertFalse(store.delete("../outside.txt"))
        assertTrue(outside.exists())
    }

    @Test
    fun commitRejectsUnsafeIds() {
        listOf("../x", "a/b", "", "a b", "a\\b", "x".repeat(65)).forEach { id ->
            val temp = store.newTempFile()
            assertThrows("id: $id", IllegalArgumentException::class.java) { store.commit(temp, id) }
            store.discardTemp(temp)
        }
    }

    @Test
    fun commitNeverOverwritesAnExistingFile() {
        val path = storeFile("same", "first")
        val temp = store.newTempFile()
        temp.writeText("second")
        assertThrows(IOException::class.java) { store.commit(temp, "same") }
        assertEquals("first", store.resolve(path)!!.readText())
        store.discardTemp(temp)
    }

    @Test
    fun discardTempDeletesTheFile() {
        val temp = store.newTempFile()
        temp.writeText("partial")
        store.discardTemp(temp)
        assertFalse(temp.exists())
    }

    @Test
    fun cleanTempRemovesLeftoversButNotImportsInProgress() {
        val active = store.newTempFile()
        active.writeText("still copying")
        val stale = File(active.parentFile, "stale-from-last-session.part")
        stale.writeText("interrupted")

        assertEquals(1, store.cleanTemp())
        assertFalse(stale.exists())
        assertTrue(active.exists())

        store.discardTemp(active)
        assertFalse(active.exists())
    }

    @Test
    fun deleteBlobsNotInKeepsKnownAndRecentFiles() {
        val known = storeFile("known")
        val oldOrphan = storeFile("old-orphan")
        val newOrphan = storeFile("new-orphan")
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        store.resolve(oldOrphan)!!.setLastModified(now - 2 * day)

        val removed = store.deleteBlobsNotIn(setOf(known), olderThanMs = day, nowMs = now)

        assertEquals(1, removed)
        assertTrue(store.resolve(known)!!.exists())
        assertFalse(store.resolve(oldOrphan)!!.exists())
        assertTrue("a just-stored file is never removed", store.resolve(newOrphan)!!.exists())

        assertEquals(1, store.deleteBlobsNotIn(setOf(known)))
        assertFalse(store.resolve(newOrphan)!!.exists())
    }

    @Test
    fun reportsFreeSpace() {
        assertTrue(store.usableSpace() > 0)
    }
}
