package com.zero7goat.taz.features.notes.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSearchTest {

    private fun matches(query: String, title: String = "", description: String = "", body: String = "") =
        NoteSearch.matches(title, description, body, query)

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(matches("", title = "anything"))
        assertTrue(matches("   "))
    }

    @Test
    fun findsWordsInTheTitleDescriptionOrBody() {
        assertTrue(matches("groceries", title = "Groceries"))
        assertTrue(matches("trip", description = "Summer trip plan"))
        assertTrue(matches("passport", body = "Remember the passport and tickets"))
        assertFalse(matches("passport", title = "Groceries", body = "milk, eggs"))
    }

    @Test
    fun ignoresUpperAndLowerCase() {
        assertTrue(matches("MILK", body = "buy milk"))
        assertTrue(matches("milk", body = "Buy MILK"))
    }

    @Test
    fun everyWordMustAppearButInAnyOrderAndAnyField() {
        assertTrue(matches("eggs milk", body = "milk and eggs"))
        assertTrue(matches("trip tickets", title = "Trip", body = "buy tickets"))
        assertFalse(matches("eggs butter", body = "milk and eggs"))
    }

    @Test
    fun wordsAreDeduplicatedAndLowerCased() {
        assertEquals(listOf("a", "b"), NoteSearch.words("  A b  a "))
        assertTrue(NoteSearch.words("   ").isEmpty())
    }

    @Test
    fun snippetShowsTextAroundTheMatch() {
        val body = "x".repeat(200) + " the secret recipe is here " + "y".repeat(200)
        val snippet = NoteSearch.snippet("", body, "recipe")
        assertNotNull(snippet)
        assertTrue(snippet!!.contains("recipe"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.length < 130)
    }

    @Test
    fun snippetOfAMatchAtTheStartHasNoLeadingEllipsis() {
        val snippet = NoteSearch.snippet("", "Recipe for soup", "recipe")
        assertEquals("Recipe for soup", snippet)
    }

    @Test
    fun snippetTurnsLineBreaksIntoSpacesAndHidesImageReferences() {
        val body = "first line\n\n![photo](taz-image://abc.jpg)\nsecond line with needle"
        val snippet = NoteSearch.snippet("", body, "needle")!!
        assertFalse(snippet.contains("\n"))
        assertFalse(snippet.contains("taz-image"))
        assertTrue(snippet.contains("[image]"))
        assertTrue(snippet.contains("needle"))
    }

    @Test
    fun noSnippetWhenNothingInTheTextMatchesOrQueryIsBlank() {
        assertNull(NoteSearch.snippet("", "nothing relevant", "zebra"))
        assertNull(NoteSearch.snippet("", "text", ""))
        assertNull(NoteSearch.snippet("", "", "zebra"))
    }

    @Test
    fun highlightMarksEveryOccurrenceIgnoringCase() {
        val ranges = NoteSearch.highlightRanges("Milk and milk", "milk")
        assertEquals(listOf(0..3, 9..12), ranges)
    }

    @Test
    fun highlightMergesOverlappingAndTouchingMatches() {
        assertEquals(listOf(0..4), NoteSearch.highlightRanges("abcdef", "abc cde"))
        assertEquals(listOf(0..5), NoteSearch.highlightRanges("abcdef", "abc def"))
    }

    @Test
    fun highlightOfBlankQueryOrTextIsEmpty() {
        assertTrue(NoteSearch.highlightRanges("text", "").isEmpty())
        assertTrue(NoteSearch.highlightRanges("", "x").isEmpty())
    }
}
