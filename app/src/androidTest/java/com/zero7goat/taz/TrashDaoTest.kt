package com.zero7goat.taz

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.features.notes.data.NoteEntity
import com.zero7goat.taz.features.passwords.data.CredentialEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Trash behaviour against a real (in-memory) Room database. Synthetic data only. */
@RunWith(AndroidJUnit4::class)
class TrashDaoTest {

    private lateinit var db: TazDatabase

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TazDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun note(id: String, deletedAt: Long? = null) = NoteEntity(
        id = id, folderId = null, title = "Note $id", description = "", body = "text $id",
        createdAt = 1, updatedAt = 2, isFavorite = false, isPinned = false, colorIndex = 0, deletedAt = deletedAt
    )

    @Test
    fun trashedNotesLeaveTheListAndComeBackOnRestore() = runBlocking {
        val dao = db.noteDao()
        dao.upsert(note("a"))
        dao.upsert(note("b"))

        dao.moveToTrash("a", 5_000)

        assertEquals(listOf("b"), dao.observeAll().first().map { it.id })
        assertEquals(listOf("a"), dao.observeTrash().first().map { it.id })
        assertEquals(listOf("b"), dao.getAllActive().map { it.id }) // backups and exports skip the trash
        assertEquals(setOf("a", "b"), dao.getAll().map { it.id }.toSet()) // restore recovery sees everything

        dao.restoreFromTrash("a")

        assertEquals(setOf("a", "b"), dao.observeAll().first().map { it.id }.toSet())
        assertTrue(dao.observeTrash().first().isEmpty())
    }

    @Test
    fun expiredLookupFindsOnlyItemsDeletedBeforeTheCutoff() = runBlocking {
        val dao = db.noteDao()
        dao.upsert(note("old", deletedAt = 1_000))
        dao.upsert(note("recent", deletedAt = 9_000))
        dao.upsert(note("live"))

        assertEquals(listOf("old"), dao.trashedBefore(5_000))
        assertEquals(setOf("old", "recent"), dao.trashedIds().toSet())
    }

    @Test
    fun trashedNoteBodiesStillCountAsReferencingTheirImages() = runBlocking {
        val dao = db.noteDao()
        dao.upsert(note("a", deletedAt = 1_000))
        assertEquals(listOf("text a"), dao.getAllBodies())
    }

    @Test
    fun trashedLoginsAreHiddenFromTheListAndKeepTheirEncryptedData() = runBlocking {
        val dao = db.credentialDao()
        dao.upsert(login("c1"))
        dao.upsert(login("c2"))

        dao.moveToTrash("c1", 5_000)

        assertEquals(listOf("c2"), dao.observeListRows().first().map { it.id })
        assertEquals(listOf("c1"), dao.observeTrash().first().map { it.id })
        assertEquals(listOf("c2"), dao.getAllActive().map { it.id })
        val kept = dao.getById("c1")
        assertNotNull(kept)
        assertTrue(kept!!.passwordCipher.contentEquals(byteArrayOf(4, 5, 6)))

        dao.restoreFromTrash("c1")
        assertEquals(setOf("c1", "c2"), dao.observeListRows().first().map { it.id }.toSet())
    }

    @Test
    fun purgingRemovesTheRowForGood() = runBlocking {
        val dao = db.noteDao()
        dao.upsert(note("a", deletedAt = 1_000))
        dao.deleteById("a")
        assertNull(dao.getById("a"))
    }

    private fun login(id: String) = CredentialEntity(
        id = id, folderId = null, serviceName = "Service $id", websiteUrl = "", domain = "",
        usernameCipher = byteArrayOf(1, 2, 3), passwordCipher = byteArrayOf(4, 5, 6), noteCipher = byteArrayOf(7, 8, 9),
        isFavorite = false, createdAt = 1, updatedAt = 2
    )
}
