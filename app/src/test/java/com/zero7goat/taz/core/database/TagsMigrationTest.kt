package com.zero7goat.taz.core.database

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Runs the real version 6 to 7 migration SQL (tags, categories, custom fields) against an
 * in-memory SQLite database built from the exported version 6 schema, filled with synthetic data.
 * Proves that every existing row, value and ciphertext survives byte for byte, that the result is
 * exactly what Room expects for version 7, and that a failure part-way leaves version 6 untouched.
 */
class TagsMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        createSchema(6)
        seedVersion6()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun migrate() = TazMigrations.MIGRATION_6_7_SQL.forEach { sql -> db.createStatement().use { it.execute(sql) } }

    @Test
    fun everyExistingValueAndCiphertextSurvivesUnchanged() {
        val before = snapshot()
        migrate()
        assertEquals(before, snapshot())
    }

    @Test
    fun existingLoginsBecomeTheLoginCategoryWithNoCustomFields() {
        migrate()
        assertEquals(2, count("SELECT COUNT(*) FROM credentials WHERE category = 'login'"))
        assertEquals(0, count("SELECT COUNT(*) FROM credentials WHERE category <> 'login'"))
        assertEquals(2, count("SELECT COUNT(*) FROM credentials WHERE fieldsCipher IS NULL"))
        // The trash marker of a login in the trash is kept.
        assertEquals(1, count("SELECT COUNT(*) FROM credentials WHERE deletedAt IS NOT NULL"))
    }

    @Test
    fun newTablesStartEmpty() {
        migrate()
        listOf("tags", "item_tags", "credential_categories", "note_fields").forEach {
            assertEquals(it, 0, count("SELECT COUNT(*) FROM `$it`"))
        }
    }

    @Test
    fun tablesMatchTheExportedVersion7Schema() {
        migrate()
        val entities = loadSchema(7).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            val expected = mutableMapOf<String, Pair<String, Boolean>>()
            val fields = entity.getJSONArray("fields")
            for (j in 0 until fields.length()) {
                val f = fields.getJSONObject(j)
                expected[f.getString("columnName")] = f.getString("affinity") to f.optBoolean("notNull", false)
            }
            val actual = mutableMapOf<String, Pair<String, Boolean>>()
            db.createStatement().use { s ->
                s.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                    while (rs.next()) actual[rs.getString("name")] = rs.getString("type") to (rs.getInt("notnull") == 1)
                }
            }
            assertEquals("columns of $table", expected, actual)
        }
    }

    @Test
    fun migrationOnlyCreatesAndAddsAndNeverDeletesOrRewrites() {
        val destructive = Regex("\\b(DROP|DELETE|UPDATE|TRUNCATE|REPLACE|RENAME)\\b", RegexOption.IGNORE_CASE)
        // Foreign-key actions ("ON DELETE CASCADE") describe new tables; they are not statements.
        val foreignKeyActions = Regex("ON (DELETE|UPDATE) (CASCADE|NO ACTION|SET NULL)", RegexOption.IGNORE_CASE)
        assertFalse(TazMigrations.MIGRATION_6_7_SQL.any { destructive.containsMatchIn(foreignKeyActions.replace(it, "")) })
        assertTrue(
            TazMigrations.MIGRATION_6_7_SQL.all {
                it.startsWith("CREATE TABLE IF NOT EXISTS") || it.startsWith("CREATE INDEX IF NOT EXISTS") ||
                    it.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS") || (it.startsWith("ALTER TABLE") && it.contains("ADD COLUMN"))
            }
        )
    }

    @Test
    fun interruptedMigrationLeavesVersion6Untouched() {
        val before = snapshot()
        db.autoCommit = false
        try {
            TazMigrations.MIGRATION_6_7_SQL.take(5).forEach { sql -> db.createStatement().use { it.execute(sql) } }
            db.createStatement().use { it.execute("THIS IS NOT VALID SQL") } // simulated failure part-way
            fail("expected the invalid statement to fail")
        } catch (expected: java.sql.SQLException) {
            db.rollback()
        } finally {
            db.autoCommit = true
        }
        assertEquals(before, snapshot())
        assertFalse(tables().contains("tags"))
        assertFalse(columns("credentials").contains("category"))
    }

    @Test
    fun deletingATagRemovesOnlyItsLinksNeverItems() {
        migrate()
        db.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys = ON")
            s.execute("INSERT INTO tags VALUES ('t1','Work','work',1)")
            s.execute("INSERT INTO item_tags VALUES ('t1','NOTE','n1')")
            s.execute("INSERT INTO item_tags VALUES ('t1','LOGIN','c1')")
            s.execute("DELETE FROM tags WHERE id = 't1'")
        }
        assertEquals(0, count("SELECT COUNT(*) FROM item_tags"))
        assertEquals(2, count("SELECT COUNT(*) FROM notes"))
        assertEquals(2, count("SELECT COUNT(*) FROM credentials"))
    }

    @Test
    fun tagNamesAreUniqueIgnoringCase() {
        migrate()
        db.createStatement().use { it.execute("INSERT INTO tags VALUES ('t1','Work','work',1)") }
        try {
            db.createStatement().use { it.execute("INSERT INTO tags VALUES ('t2','WORK','work',2)") }
            fail("a second tag with the same normalized name must be refused")
        } catch (expected: java.sql.SQLException) {
            // The unique index refused it.
        }
    }

    @Test
    fun migrationListIsRegisteredAndContinuousFrom1To7() {
        val versions = TazMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7), versions)
    }

    // ---------- helpers ----------

    private fun schemaDir() = File("schemas/com.zero7goat.taz.core.database.TazDatabase")

    private fun loadSchema(version: Int): JSONObject {
        val file = File(schemaDir(), "$version.json")
        assertTrue("Room has not exported ${file.path}; build the project once", file.isFile)
        return JSONObject(file.readText())
    }

    private fun createSchema(version: Int) {
        val entities = loadSchema(version).getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.createStatement().use { it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", table)) }
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.createStatement().use {
                    it.execute(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
    }

    private fun seedVersion6() {
        db.createStatement().use { s ->
            s.execute("INSERT INTO folders VALUES ('f1','PASSWORDS','Banking',NULL,1000)")
            s.execute("INSERT INTO notes VALUES ('n1',NULL,'Title','Desc','Body **bold**',2000,3000,1,0,2,NULL)")
            s.execute("INSERT INTO notes VALUES ('n2',NULL,'Trashed','','',2001,3001,0,1,0,9999)")
            s.execute("INSERT INTO files VALUES ('x1',NULL,'a.pdf','a.pdf','application/pdf',123,'vault/files/x1','k1,k2','d',0,6000,7000,NULL)")
        }
        db.prepareStatement("INSERT INTO credentials VALUES (?,?,?,?,?,?,?,?,?,?,?,?)").use { p ->
            listOf("c1" to null, "c2" to 8888L).forEachIndexed { index, (id, deletedAt) ->
                p.setString(1, id); p.setString(2, "f1"); p.setString(3, "Example $index"); p.setString(4, "https://example.test")
                p.setString(5, "example.test")
                p.setBytes(6, byteArrayOf(1, 2, 3, index.toByte())); p.setBytes(7, byteArrayOf(4, 5, 6, index.toByte()))
                p.setBytes(8, byteArrayOf(7, 8, 9, index.toByte())); p.setInt(9, 1); p.setLong(10, 4000); p.setLong(11, 5000)
                if (deletedAt == null) p.setNull(12, java.sql.Types.INTEGER) else p.setLong(12, deletedAt)
                p.executeUpdate()
            }
        }
    }

    private fun tables(): List<String> = dump("SELECT name FROM sqlite_master WHERE type = 'table'")

    private fun columns(table: String): List<String> {
        val names = ArrayList<String>()
        db.createStatement().use { s ->
            s.executeQuery("PRAGMA table_info(`$table`)").use { rs -> while (rs.next()) names.add(rs.getString("name")) }
        }
        return names
    }

    private fun count(sql: String): Int =
        db.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) } }

    /** Every row of the version 6 tables, restricted to the version 6 columns, as hex text. */
    private fun snapshot(): Map<String, List<String>> {
        val v6Columns = mapOf(
            "folders" to listOf("id", "module", "name", "parentId", "createdAt"),
            "notes" to listOf("id", "folderId", "title", "description", "body", "createdAt", "updatedAt", "isFavorite", "isPinned", "colorIndex", "deletedAt"),
            "credentials" to listOf("id", "folderId", "serviceName", "websiteUrl", "domain", "usernameCipher", "passwordCipher", "noteCipher", "isFavorite", "createdAt", "updatedAt", "deletedAt"),
            "files" to listOf("id", "folderId", "originalName", "displayName", "mimeType", "sizeBytes", "storedPath", "keywords", "description", "isFavorite", "createdAt", "updatedAt", "deletedAt")
        )
        return v6Columns.mapValues { (table, cols) ->
            dump("SELECT ${cols.joinToString { "hex(CAST(`$it` AS BLOB))" }} FROM `$table` ORDER BY id")
        }
    }

    private fun dump(sql: String): List<String> {
        val rows = ArrayList<String>()
        db.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                val n = rs.metaData.columnCount
                while (rs.next()) rows.add((1..n).joinToString("|") { rs.getString(it) ?: "NULL" })
            }
        }
        return rows
    }
}
