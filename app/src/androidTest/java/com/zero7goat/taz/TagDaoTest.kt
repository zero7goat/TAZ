package com.zero7goat.taz

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.tags.TagRepository
import com.zero7goat.taz.core.tags.TagResult
import com.zero7goat.taz.core.tags.TagTarget
import com.zero7goat.taz.features.notes.data.NoteEntity
import com.zero7goat.taz.features.notes.data.NoteFieldsEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tags and note fields against a real (in-memory) Room database. Synthetic data only. */
@RunWith(AndroidJUnit4::class)
class TagDaoTest {

    private lateinit var db: TazDatabase
    private lateinit var tags: TagRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, TazDatabase::class.java).allowMainThreadQueries().build()
        tags = TagRepository(db.tagDao())
    }

    @After
    fun tearDown() = db.close()

    private fun note(id: String, deletedAt: Long? = null) = NoteEntity(
        id = id, folderId = null, title = "Note $id", description = "", body = "text",
        createdAt = 1, updatedAt = 2, isFavorite = false, isPinned = false, colorIndex = 0, deletedAt = deletedAt
    )

    @Test
    fun creatingTheSameNameTwiceReturnsTheExistingTag() = runBlocking {
        val first = (tags.createOrGet("Work") as TagResult.Ok).tag
        val second = (tags.createOrGet("  work ") as TagResult.Ok).tag
        assertEquals(first.id, second.id)
        assertEquals(1, db.tagDao().getAll().size)
    }

    @Test
    fun renamingOntoAnotherTagIsRefusedButCaseChangesAreAllowed() = runBlocking {
        val work = (tags.createOrGet("work") as TagResult.Ok).tag
        tags.createOrGet("Home")
        assertTrue(tags.rename(work.id, "HOME") is TagResult.Duplicate)
        assertEquals("Work", (tags.rename(work.id, "Work") as TagResult.Ok).tag.name)
    }

    @Test
    fun deletingATagKeepsTheItems() = runBlocking {
        db.noteDao().upsert(note("n1"))
        val tag = (tags.createOrGet("Work") as TagResult.Ok).tag
        tags.setItemTags(TagTarget.NOTE, "n1", setOf(tag.id))
        tags.delete(tag.id)
        assertTrue(db.tagDao().getAllLinks().isEmpty())
        assertEquals("Note n1", db.noteDao().getById("n1")?.title)
    }

    @Test
    fun usageCountsIgnoreTheTrashAndOrphansAreCleanedUp() = runBlocking {
        db.noteDao().upsert(note("n1"))
        db.noteDao().upsert(note("n2", deletedAt = 5))
        val tag = (tags.createOrGet("Work") as TagResult.Ok).tag
        tags.addTags(TagTarget.NOTE, listOf("n1", "n2", "never-saved"), setOf(tag.id))
        assertEquals(1, tags.observeUsage().first().single().usage)
        assertEquals(1, tags.cleanupOrphans()) // only the link of the never-saved note
        assertEquals(2, db.tagDao().getAllLinks().size) // the trashed note keeps its tag for restore
    }

    @Test
    fun noteFieldsAreSavedWithTheNoteAndRemovedWithIt() = runBlocking {
        db.noteDao().saveWithFields(note("n1"), NoteFieldsEntity("n1", byteArrayOf(1, 2, 3)))
        assertTrue(db.noteDao().getFields("n1")!!.fieldsCipher.contentEquals(byteArrayOf(1, 2, 3)))
        db.noteDao().saveWithFields(note("n1"), null)
        assertNull(db.noteDao().getFields("n1"))
        db.noteDao().saveWithFields(note("n1"), NoteFieldsEntity("n1", byteArrayOf(4)))
        db.noteDao().deleteById("n1")
        assertNull(db.noteDao().getFields("n1"))
    }
}
