package com.zero7goat.taz.core.ui

import com.zero7goat.taz.core.database.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CollectionHelpersTest {

    private val folders = listOf(
        FolderEntity("a", "NOTES", "Work", null, 1),
        FolderEntity("b", "NOTES", "Projects", "a", 2),
        FolderEntity("c", "NOTES", "2026", "b", 3)
    )

    @Test
    fun pathLabelShowsEveryLevel() {
        assertEquals("Work / Projects / 2026", folderPathLabel(folders, "c", "Notes"))
        assertEquals("Work", folderPathLabel(folders, "a", "Notes"))
    }

    @Test
    fun topLevelAndMissingFoldersUseTheRootLabel() {
        assertEquals("Notes", folderPathLabel(folders, null, "Notes"))
        assertEquals("Notes", folderPathLabel(folders, "gone", "Notes"))
    }

    @Test
    fun aLoopInDamagedDataDoesNotHang() {
        val loop = listOf(FolderEntity("x", "NOTES", "X", "y", 1), FolderEntity("y", "NOTES", "Y", "x", 2))
        folderPathLabel(loop, "x", "Notes") // must return
    }

    @Test
    fun selectionDropsItemsThatAreNoLongerVisible() {
        assertEquals(setOf("1"), setOf("1", "2").keepVisible(listOf("1", "3")))
        assertEquals(emptySet<String>(), setOf("1").keepVisible(emptyList()))
    }

    @Test
    fun selectionIsUnchangedWhenEverythingIsStillVisible() {
        val selection = setOf("1", "2")
        assertSame(selection, selection.keepVisible(listOf("1", "2", "3")))
    }
}
