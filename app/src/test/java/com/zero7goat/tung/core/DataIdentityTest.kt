package com.zero7goat.tung.core

import com.zero7goat.tung.core.database.TungDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app was renamed to TAZ, but its stored data must stay reachable. The database file keeps its
 * original name (the application id, Keystore key alias and encryption context strings are
 * unchanged too, and are covered by the cipher tests). If this test ever fails, existing users'
 * notes, logins and files would look empty after an update.
 */
class DataIdentityTest {

    @Test
    fun databaseKeepsItsOriginalFileName() {
        assertEquals("tung.db", TungDatabase.DATABASE_NAME)
    }
}
