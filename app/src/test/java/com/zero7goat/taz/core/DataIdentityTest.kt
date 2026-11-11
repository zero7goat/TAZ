package com.zero7goat.taz.core

import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.features.notes.data.NoteRepository
import com.zero7goat.taz.features.passwords.data.CredentialRepository
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

    @Test
    fun encryptionContextsOfLoginFieldsAndNoteFieldsNeverChange() {
        // Existing logins (1.0 / 1.1) and their new custom fields (1.2) are bound to these exact strings.
        assertEquals("taz.credential.v1|id-1|password", CredentialRepository.aad("id-1", "password"))
        assertEquals("taz.credential.v1|id-1|fields", CredentialRepository.aad("id-1", "fields"))
        assertEquals("taz.note.v1|n-1|fields", NoteRepository.fieldsAad("n-1"))
    }
}
