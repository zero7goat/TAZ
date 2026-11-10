package com.zero7goat.taz.core.database

/**
 * One item in the trash, as read from the database: just enough to list it. [label] is the note
 * title, the login's service name or the file's display name. Never carries any secret.
 */
data class TrashRow(
    val id: String,
    val label: String,
    val deletedAt: Long
)
