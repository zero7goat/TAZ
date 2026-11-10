package com.zero7goat.taz

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.database.TazMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens real databases created at older schema versions, migrates them with the app's migrations
 * and lets Room validate the result against the exported schema. Uses synthetic data only.
 * Run from Android Studio on a device or emulator (these cannot run on the plain JVM).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TazDatabase::class.java)

    @Test
    fun version5To6KeepsEveryRowAndAddsTheTrashColumn() {
        helper.createDatabase(DB, 5).apply {
            execSQL("INSERT INTO folders VALUES ('f1','notes','Work',NULL,1000)")
            execSQL(
                "INSERT INTO notes (id,folderId,title,description,body,createdAt,updatedAt,isFavorite,isPinned,colorIndex) " +
                    "VALUES ('n1','f1','Title','Desc','Body',2000,3000,1,1,2)"
            )
            execSQL(
                "INSERT INTO credentials (id,folderId,serviceName,websiteUrl,domain,usernameCipher,passwordCipher,noteCipher," +
                    "isFavorite,createdAt,updatedAt) VALUES ('c1','f1','Example','https://example.test','example.test'," +
                    "x'010203',x'040506',x'070809',1,4000,5000)"
            )
            execSQL(
                "INSERT INTO files (id,folderId,originalName,displayName,mimeType,sizeBytes,storedPath,keywords,description," +
                    "isFavorite,createdAt,updatedAt) VALUES ('x1','f1','a.pdf','a.pdf','application/pdf',123,'vault/files/x1'," +
                    "'k','d',0,6000,7000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 6, true, *TazMigrations.ALL)

        db.query("SELECT title, body, isFavorite, isPinned, colorIndex, deletedAt FROM notes WHERE id = 'n1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Title", it.getString(0))
            assertEquals("Body", it.getString(1))
            assertEquals(1, it.getInt(2))
            assertEquals(1, it.getInt(3))
            assertEquals(2, it.getInt(4))
            assertTrue(it.isNull(5))
        }
        db.query("SELECT hex(passwordCipher), deletedAt FROM credentials WHERE id = 'c1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("040506", it.getString(0))
            assertTrue(it.isNull(1))
        }
        db.query("SELECT storedPath, deletedAt FROM files WHERE id = 'x1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("vault/files/x1", it.getString(0))
            assertTrue(it.isNull(1))
        }
    }

    @Test
    fun anOldVersion2NoteSurvivesTheWholeMigrationChain() {
        helper.createDatabase(DB, 2).apply {
            execSQL(
                "INSERT INTO notes (id,title,description,body,createdAt,updatedAt,isFavorite,isPinned,colorIndex) " +
                    "VALUES ('old','Old note','','kept',1,2,0,0,0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 6, true, *TazMigrations.ALL)

        db.query("SELECT title, body, folderId, deletedAt FROM notes WHERE id = 'old'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Old note", it.getString(0))
            assertEquals("kept", it.getString(1))
            assertTrue(it.isNull(2))
            assertTrue(it.isNull(3))
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
