package com.zero7goat.taz.features.search.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSearchTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private val docs = listOf(
        SearchDoc(
            ResultType.NOTE, "n1", "Holiday plan", listOf(SearchField("Text", "Book the train to Lisbon in May")),
            tagIds = setOf("t-travel"), tagNames = listOf("Travel"), folderId = "f-notes-child", isFavorite = true, updatedAt = now - 2 * day
        ),
        SearchDoc(
            ResultType.LOGIN, "c1", "Example Bank", listOf(SearchField("Username", "alice@example.test"), SearchField("Category", "Login")),
            tagIds = setOf("t-money"), tagNames = listOf("Money"), folderId = null, isFavorite = false, updatedAt = now - 40 * day
        ),
        SearchDoc(
            ResultType.FILE, "x1", "scan.pdf", listOf(SearchField("Keywords", "tax, 2026")),
            tagIds = setOf("t-money", "t-travel"), tagNames = listOf("Money", "Travel"), folderId = "f-files", isFavorite = true, updatedAt = now - 400 * day
        ),
        SearchDoc(
            ResultType.FOLDER, "f-notes-child", "Trips", emptyList(), emptySet(), emptyList(),
            folderId = "f-notes", isFavorite = false, updatedAt = now - 5 * day
        )
    )
    private val parents = mapOf("f-notes" to null, "f-notes-child" to "f-notes", "f-files" to null)

    private fun search(query: String, filters: SearchFilters = SearchFilters()) =
        VaultSearch.search(docs, query, filters, now, parents).map { it.doc.id }

    @Test
    fun everyWordMustMatchSomewhereIgnoringCaseAndSpacing() {
        assertEquals(listOf("n1"), search("  LISBON   train "))
        assertTrue(search("lisbon bank").isEmpty())
        assertEquals(listOf("c1"), search("alice bank"))
    }

    @Test
    fun tagNamesAndFolderNamesAreSearched() {
        assertEquals(setOf("c1", "x1"), search("money").toSet())
        assertEquals(listOf("f-notes-child"), search("trips"))
    }

    @Test
    fun blankQueryWithoutFiltersShowsNothingButFiltersAloneBrowse() {
        assertTrue(search("   ").isEmpty())
        assertEquals(setOf("n1", "x1"), search("", SearchFilters(favoritesOnly = true)).toSet())
    }

    @Test
    fun typeFavoriteTagAndDateFiltersCombine() {
        assertEquals(listOf("x1"), search("", SearchFilters(types = setOf(ResultType.FILE))))
        // Several tags: items need all of them.
        assertEquals(listOf("x1"), search("", SearchFilters(tagIds = setOf("t-money", "t-travel"))))
        assertEquals(setOf("n1", "f-notes-child"), search("", SearchFilters(modified = DateRange.WEEK)).toSet())
        assertEquals(setOf("n1", "c1", "f-notes-child"), search("", SearchFilters(modified = DateRange.YEAR)).toSet())
    }

    @Test
    fun folderFilterIncludesSubfolders() {
        assertEquals(setOf("n1", "f-notes-child"), search("", SearchFilters(folderId = "f-notes")).toSet())
        assertEquals(listOf("x1"), search("", SearchFilters(folderId = "f-files")))
    }

    @Test
    fun titleMatchesComeFirstAndTheMatchedFieldIsExplained() {
        val hits = VaultSearch.search(docs, "example", SearchFilters(), now, parents)
        assertEquals("c1", hits.first().doc.id)
        val byUsername = VaultSearch.search(docs, "alice", SearchFilters(), now, parents).single()
        assertEquals("Username", byUsername.matchedLabel)
        assertTrue(byUsername.snippet!!.contains("alice"))
    }

    @Test
    fun highlightRangesCoverEveryWordAndMerge() {
        assertEquals(listOf(0..6, 13..17), VaultSearch.highlightRanges("Holiday plan Train", "holiday train"))
        assertEquals(listOf(0..3), VaultSearch.highlightRanges("abcd", "ab bcd"))
        assertTrue(VaultSearch.highlightRanges("text", "  ").isEmpty())
    }

    @Test
    fun snippetsAreShortAndCentredOnTheMatch() {
        val long = "x ".repeat(200) + "needle" + " y".repeat(200)
        val snippet = VaultSearch.snippet(long, "needle")
        assertTrue(snippet.contains("needle"))
        assertTrue(snippet.length < 120)
        assertTrue(snippet.startsWith("…") && snippet.endsWith("…"))
    }
}
