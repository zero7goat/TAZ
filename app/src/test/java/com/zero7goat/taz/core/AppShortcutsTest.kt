package com.zero7goat.taz.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShortcutsTest {

    private val now = 1_800_000_000_000L

    @Test
    fun freshRequestIsShown() {
        assertTrue(AppShortcuts.isFresh(requestedAt = now - 5_000, now = now))
        assertTrue(AppShortcuts.isFresh(requestedAt = now - AppShortcuts.MAX_AGE_MS, now = now))
    }

    @Test
    fun staleOrMissingRequestIsIgnored() {
        assertFalse(AppShortcuts.isFresh(requestedAt = now - AppShortcuts.MAX_AGE_MS - 1, now = now))
        assertFalse(AppShortcuts.isFresh(requestedAt = 0L, now = now))
        assertFalse(AppShortcuts.isFresh(requestedAt = now + 60_000, now = now)) // clock moved back
    }

    @Test
    fun requestIsRememberedUntilCleared() {
        AppShortcuts.clearNewNote()
        assertEquals(0L, AppShortcuts.newNoteRequestedAt.value)
        AppShortcuts.requestNewNote(now)
        assertEquals(now, AppShortcuts.newNoteRequestedAt.value)
        AppShortcuts.clearNewNote()
        assertEquals(0L, AppShortcuts.newNoteRequestedAt.value)
    }

    @Test
    fun shortcutActionMatchesTheManifestXml() {
        assertEquals("com.zero7goat.taz.action.NEW_NOTE", AppShortcuts.ACTION_NEW_NOTE)
    }
}
