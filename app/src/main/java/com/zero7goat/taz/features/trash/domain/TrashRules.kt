package com.zero7goat.taz.features.trash.domain

import java.util.concurrent.TimeUnit

enum class TrashKind { NOTE, LOGIN, FILE }

/** One item in the trash. Only a label is kept: no password or note text ever reaches the trash list. */
data class TrashItem(
    val kind: TrashKind,
    val id: String,
    val label: String,
    val deletedAt: Long
)

/** How long deleted items stay in the trash, and the date arithmetic around it. Pure functions. */
object TrashRules {

    const val RETENTION_DAYS = 30
    private val RETENTION_MS = TimeUnit.DAYS.toMillis(RETENTION_DAYS.toLong())

    /** Items deleted before this moment are past their retention and may be purged. */
    fun expiryCutoff(now: Long): Long = now - RETENTION_MS

    /**
     * Whole days left before the item is purged, never below 0. An item that was just deleted has
     * [RETENTION_DAYS] left. A clock that moved backwards cannot extend the retention past it.
     */
    fun daysLeft(deletedAt: Long, now: Long): Int {
        val remainingMs = deletedAt + RETENTION_MS - now
        if (remainingMs <= 0) return 0
        val days = TimeUnit.MILLISECONDS.toDays(remainingMs + TimeUnit.DAYS.toMillis(1) - 1)
        return days.toInt().coerceIn(0, RETENTION_DAYS)
    }
}
