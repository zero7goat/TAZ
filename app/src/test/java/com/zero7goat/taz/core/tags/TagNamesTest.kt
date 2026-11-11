package com.zero7goat.taz.core.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNamesTest {

    @Test
    fun capitalisationAndWhitespaceNeverMakeADifferentTag() {
        val variants = listOf("Work", "work", " WORK ", "work\t", "Work")
        assertEquals(1, variants.map { TagNames.normalize(it) }.toSet().size)
        assertEquals(TagNames.normalize("Tax  2026"), TagNames.normalize(" tax 2026 "))
    }

    @Test
    fun cleanKeepsTheUsersCapitalisationButTidiesSpaces() {
        assertEquals("Tax 2026", TagNames.clean("  Tax   2026 "))
        assertEquals("", TagNames.clean("   "))
    }

    @Test
    fun longNamesAreCut() {
        assertTrue(TagNames.clean("x".repeat(200)).length <= TagNames.MAX_LENGTH)
    }
}
