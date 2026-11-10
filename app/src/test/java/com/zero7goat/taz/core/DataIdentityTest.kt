package com.zero7goat.taz.core

import com.zero7goat.taz.core.database.TazDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Names that identify stored data on a device: the database file, the Keystore key alias, the
 * DataStore files and the encryption context strings. Once TAZ is released they must never change,
 * or existing users' notes, logins and files would look empty after an update. If this test fails,
 * someone changed one of them.
 */
class DataIdentityTest {

    @Test
    fun databaseKeepsItsFileName() {
        assertEquals("taz.db", TazDatabase.DATABASE_NAME)
    }
}
