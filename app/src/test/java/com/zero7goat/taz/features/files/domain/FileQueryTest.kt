package com.zero7goat.taz.features.files.domain

import com.zero7goat.taz.core.database.FolderEntity
import com.zero7goat.taz.features.files.data.FileEntity
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileQueryTest {

    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
    private val day = 24L * 60 * 60 * 1000

    private fun folder(id: String, name: String, parent: String? = null) =
        folderFixture(id, name, parent)

    private fun file(
        id: String,
        name: String,
        folderId: String? = null,
        mime: String = "application/octet-stream",
        size: Long = 100,
        added: Long = now,
        keywords: String = "",
        description: String = "",
        favorite: Boolean = false
    ) = FileEntity(
        id = id, folderId = folderId, originalName = name, displayName = name, mimeType = mime,
        sizeBytes = size, storedPath = "vault/files/$id", keywords = keywords, description = description,
        isFavorite = favorite, createdAt = added, updatedAt = added
    )

    private val folders = listOf(
        folder("f-work", "Work"),
        folder("f-tax", "Taxes", parent = "f-work"),
        folder("f-trips", "Trips")
    )

    private fun select(files: List<FileEntity>, query: FilesQuery = FilesQuery()) =
        selectFiles(files, folders, query, now, utc)

    private fun FileSelection.names() = files.map { it.displayName }

    @Test
    fun browsingTheTopLevelShowsTopLevelFilesAndFolders() {
        val files = listOf(file("1", "b.txt"), file("2", "A.txt"), file("3", "c.txt", folderId = "f-work"))
        val result = select(files)
        assertTrue(result.isBrowsing)
        assertEquals(listOf("A.txt", "b.txt"), result.names())
        assertEquals(listOf("Work", "Trips"), result.folders.map { it.name })
    }

    @Test
    fun browsingAFolderShowsItsContentsAndPath() {
        val files = listOf(file("1", "a.txt"), file("2", "w.txt", folderId = "f-work"), file("3", "t.txt", folderId = "f-tax"))
        val result = select(files, FilesQuery(folderId = "f-tax"))
        assertEquals(listOf("t.txt"), result.names())
        assertEquals(listOf("Work", "Taxes"), result.path.map { it.name })
        assertEquals("Taxes", result.currentFolder?.name)

        val work = select(files, FilesQuery(folderId = "f-work"))
        assertEquals(listOf("w.txt"), work.names())
        assertEquals(listOf("Taxes"), work.folders.map { it.name })
    }

    @Test
    fun aFileWhoseFolderNoLongerExistsAppearsAtTheTopLevel() {
        val result = select(listOf(file("1", "orphan.txt", folderId = "deleted-folder")))
        assertEquals(listOf("orphan.txt"), result.names())
    }

    @Test
    fun aMissingCurrentFolderFallsBackToTheTopLevel() {
        val result = select(listOf(file("1", "a.txt")), FilesQuery(folderId = "gone"))
        assertEquals(null, result.currentFolder)
        assertEquals(listOf("a.txt"), result.names())
    }

    @Test
    fun searchMatchesNameKeywordsDescriptionAndFolderName() {
        val files = listOf(
            file("1", "Invoice March.pdf"),
            file("2", "scan.png", keywords = "receipt,travel"),
            file("3", "notes.txt", description = "Meeting about the Budget"),
            file("4", "x.bin", folderId = "f-trips"),
            file("5", "unrelated.doc")
        )
        assertEquals(listOf("Invoice March.pdf"), select(files, FilesQuery(text = "invoice")).names())
        assertEquals(listOf("scan.png"), select(files, FilesQuery(text = "RECEIPT")).names())
        assertEquals(listOf("notes.txt"), select(files, FilesQuery(text = "budget")).names())
        assertEquals(listOf("x.bin"), select(files, FilesQuery(text = "trips")).names())
        assertEquals(emptyList<String>(), select(files, FilesQuery(text = "nothing here")).names())
    }

    @Test
    fun searchCoversAllFoldersAndListsMatchingFolders() {
        val files = listOf(file("1", "tax-return.pdf", folderId = "f-tax"), file("2", "other.pdf"))
        val result = select(files, FilesQuery(text = "tax"))
        assertFalse(result.isBrowsing)
        assertEquals(listOf("tax-return.pdf"), result.names())
        assertEquals(listOf("Taxes"), result.folders.map { it.name })
    }

    @Test
    fun favoritesAndFiltersSearchEverywhere() {
        val files = listOf(file("1", "a.txt", favorite = true, folderId = "f-work"), file("2", "b.txt"))
        val favorites = select(files, FilesQuery(favoritesOnly = true))
        assertFalse(favorites.isBrowsing)
        assertEquals(listOf("a.txt"), favorites.names())
        assertTrue(favorites.folders.isEmpty())
    }

    @Test
    fun filtersByType() {
        val files = listOf(file("1", "p.png", mime = "image/png"), file("2", "d.pdf", mime = "application/pdf"))
        val result = select(files, FilesQuery(typeFilter = FileCategory.IMAGE))
        assertEquals(listOf("p.png"), result.names())
    }

    @Test
    fun filtersBySize() {
        val mb = 1024L * 1024
        val files = listOf(file("1", "small", size = 10), file("2", "mid", size = 5 * mb), file("3", "big", size = 50 * mb))
        assertEquals(listOf("small"), select(files, FilesQuery(sizeFilter = SizeFilter.UNDER_1_MB)).names())
        assertEquals(listOf("mid"), select(files, FilesQuery(sizeFilter = SizeFilter.MB_1_TO_10)).names())
        assertEquals(listOf("big"), select(files, FilesQuery(sizeFilter = SizeFilter.OVER_10_MB)).names())
    }

    @Test
    fun filtersByDateAdded() {
        val files = listOf(
            file("1", "today", added = now),
            file("2", "twenty days", added = now - 20 * day),
            file("3", "this year", added = Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()),
            file("4", "last year", added = Instant.parse("2025-12-31T23:59:59Z").toEpochMilli())
        )
        assertEquals(listOf("today"), select(files, FilesQuery(dateFilter = DateFilter.LAST_7_DAYS)).names())
        assertEquals(listOf("today", "twenty days"), select(files, FilesQuery(dateFilter = DateFilter.LAST_30_DAYS)).names())
        assertEquals(listOf("this year", "today", "twenty days"), select(files, FilesQuery(dateFilter = DateFilter.THIS_YEAR)).names())
    }

    @Test
    fun sorts() {
        val files = listOf(
            file("1", "b.txt", size = 30, added = now - 2 * day),
            file("2", "a.txt", size = 10, added = now),
            file("3", "c.txt", size = 20, added = now - day)
        )
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), select(files, FilesQuery(sort = FileSort.NAME_ASC)).names())
        assertEquals(listOf("c.txt", "b.txt", "a.txt"), select(files, FilesQuery(sort = FileSort.NAME_DESC)).names())
        assertEquals(listOf("b.txt", "c.txt", "a.txt"), select(files, FilesQuery(sort = FileSort.SIZE_LARGEST)).names())
        assertEquals(listOf("a.txt", "c.txt", "b.txt"), select(files, FilesQuery(sort = FileSort.SIZE_SMALLEST)).names())
        assertEquals(listOf("a.txt", "c.txt", "b.txt"), select(files, FilesQuery(sort = FileSort.ADDED_NEWEST)).names())
        assertEquals(listOf("b.txt", "c.txt", "a.txt"), select(files, FilesQuery(sort = FileSort.ADDED_OLDEST)).names())
    }

    @Test
    fun sortsByTypeThenName() {
        val files = listOf(
            file("1", "z.pdf", mime = "application/pdf"),
            file("2", "b.png", mime = "image/png"),
            file("3", "a.png", mime = "image/png")
        )
        assertEquals(listOf("a.png", "b.png", "z.pdf"), select(files, FilesQuery(sort = FileSort.TYPE)).names())
    }
}

/**
 * Builds a [FolderEntity] for these tests without depending on the order, number or types of its
 * constructor parameters. selectFiles only reads a folder's id, name and parent id, so those are
 * the only values that matter; they are set by field name after the entity is created with neutral
 * placeholder values (empty text, zero, the first enum constant) for everything else.
 */
private fun folderFixture(id: String, name: String, parentId: String?): FolderEntity {
    val constructor = FolderEntity::class.java.declaredConstructors
        .filter { !it.isSynthetic }
        .maxByOrNull { it.parameterCount }
        ?: error("FolderEntity has no constructor")
    constructor.isAccessible = true
    val arguments = constructor.parameterTypes.map { placeholderFor(it) }.toTypedArray()
    val folder = constructor.newInstance(*arguments) as FolderEntity
    setField(folder, "id", id)
    setField(folder, "name", name)
    setField(folder, "parentId", parentId)
    return folder
}

private fun placeholderFor(type: Class<*>): Any? = when {
    type == String::class.java -> ""
    type == Int::class.javaPrimitiveType -> 0
    type == Long::class.javaPrimitiveType -> 0L
    type == Boolean::class.javaPrimitiveType -> false
    type.isEnum -> type.enumConstants.first()
    else -> null
}

private fun setField(target: Any, fieldName: String, value: Any?) {
    val field = target.javaClass.getDeclaredField(fieldName)
    field.isAccessible = true
    field.set(target, value)
}
