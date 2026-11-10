package com.zero7goat.taz.core

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Requests that arrive from the home-screen shortcut. A request is only remembered, never acted on
 * here: the app shows the new note after TAZ is unlocked, so the app lock is never bypassed.
 * A request that nobody has acted on for [MAX_AGE_MS] (for example the phone was left on the lock
 * screen) is ignored, so it cannot fire at some unrelated later visit.
 */
object AppShortcuts {

    const val ACTION_NEW_NOTE = "com.zero7goat.taz.action.NEW_NOTE"
    const val MAX_AGE_MS = 2 * 60 * 1000L

    // The time the shortcut was used (epoch ms), or 0 when there is no pending request.
    private val _newNoteRequestedAt = MutableStateFlow(0L)
    val newNoteRequestedAt: StateFlow<Long> = _newNoteRequestedAt.asStateFlow()

    /** True if [intent] is the "New note" shortcut. */
    fun isNewNote(intent: Intent?): Boolean = intent?.action == ACTION_NEW_NOTE

    fun requestNewNote(now: Long = System.currentTimeMillis()) {
        _newNoteRequestedAt.value = now
    }

    /** Called once the request has been shown (or when it is no longer wanted). */
    fun clearNewNote() {
        _newNoteRequestedAt.value = 0L
    }

    /** True if a request made at [requestedAt] should still be shown at [now]. */
    fun isFresh(requestedAt: Long, now: Long): Boolean =
        requestedAt > 0L && now - requestedAt in 0..MAX_AGE_MS
}
