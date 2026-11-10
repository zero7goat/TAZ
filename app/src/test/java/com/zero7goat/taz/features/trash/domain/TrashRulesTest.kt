package com.zero7goat.taz.features.trash.domain

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRulesTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_800_000_000_000L

    @Test
    fun retentionIsThirtyDays() {
        assertEquals(30, TrashRules.RETENTION_DAYS)
    }

    @Test
    fun cutoffIsThirtyDaysBeforeNow() {
        assertEquals(now - 30 * day, TrashRules.expiryCutoff(now))
    }

    @Test
    fun justDeletedItemHasTheFullThirtyDays() {
        assertEquals(30, TrashRules.daysLeft(deletedAt = now, now = now))
    }

    @Test
    fun partialDaysRoundUpSoNothingShowsZeroBeforeItExpires() {
        assertEquals(30, TrashRules.daysLeft(now - 1_000, now))
        assertEquals(29, TrashRules.daysLeft(now - day - 1_000, now))
        assertEquals(1, TrashRules.daysLeft(now - 29 * day - 1_000, now))
    }

    @Test
    fun expiredItemHasNoDaysLeft() {
        assertEquals(0, TrashRules.daysLeft(now - 30 * day, now))
        assertEquals(0, TrashRules.daysLeft(now - 400 * day, now))
    }

    @Test
    fun clockMovingBackwardsNeverShowsMoreThanThirtyDays() {
        assertEquals(30, TrashRules.daysLeft(deletedAt = now + 10 * day, now = now))
    }

    @Test
    fun itemDeletedBeforeTheCutoffIsExpiredAndOneAfterIsNot() {
        val cutoff = TrashRules.expiryCutoff(now)
        assertTrue(now - 31 * day < cutoff)
        assertTrue(now - 29 * day > cutoff)
    }
}
