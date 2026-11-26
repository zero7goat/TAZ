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
 * Runs the real version 7 to 8 migration SQL (TAZ 2.0) against an in-memory SQLite database built
 * from the exported version 7 schema and filled with synthetic data. Proves every existing value
 * and ciphertext survives, new columns get safe defaults, the result matches what Room expects for
 * version 8, and a failure part-way leaves version 7 untouched.
 */
class Migration8Test {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        createSchema(7)
        seed()
    }

    @After
    fun tearDown() = db.close()

    private fun migrate() = TazMigrations.MIGRATION_7_8_SQL.forEach { sql -> db.createStatement().use { it.execute(sql) } }

    @Test
    fun everyExistingValueSurvivesAndNewColumnsHaveSafeDefaults() {
        val before = snapshot()
        migrate()
        assertEquals(before, snapshot())
        assertEquals(2, count("SELECT COUNT(*) FROM credentials WHERE isLocked = 0 AND historyCipher IS NULL"))
        assertEquals(1, count("SELECT COUNT(*) FROM notes WHERE isLocked = 0 AND remindAt IS NULL"))
        // Existing files are marked plain (0); they are encrypted afterwards, one by one, by the app.
        assertEquals(1, count("SELECT COUNT(*) FROM files WHERE encryption = 0 AND isLocked = 0"))
        listOf("app_links", "note_versions", "note_templates", "passkeys").forEach { assertEquals(it, 0, count("SELECT COUNT(*) FROM `$it`")) }
    }

    @Test
    fun tablesMatchTheExportedVersion8Schema() {
        migrate()
        val entities = loadSchema(8).getJSONObject("database").getJSONArray("entities")
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
    fun migrationOnlyCreatesAndAdds() {
        val destructive = Regex("\\b(DROP|DELETE|UPDATE|TRUNCATE|REPLACE|RENAME)\\b", RegexOption.IGNORE_CASE)
        val foreignKeyActions = Regex("ON (DELETE|UPDATE) (CASCADE|NO ACTION|SET NULL)", RegexOption.IGNORE_CASE)
        assertFalse(TazMigrations.MIGRATION_7_8_SQL.any { destructive.containsMatchIn(foreignKeyActions.replace(it, "")) })
        assertTrue(
            TazMigrations.MIGRATION_7_8_SQL.all {
                it.startsWith("CREATE TABLE IF NOT EXISTS") || it.startsWith("CREATE INDEX IF NOT EXISTS") ||
                    it.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS") ||
                    (it.startsWith("ALTER TABLE") && it.contains("ADD COLUMN"))
            }
        )
    }

    @Test
    fun interruptedMigrationLeavesVersion7Untouched() {
        val before = snapshot()
        db.autoCommit = false
        try {
            TazMigrations.MIGRATION_7_8_SQL.take(6).forEach { sql -> db.createStatement().use { it.execute(sql) } }
            db.createStatement().use { it.execute("THIS IS NOT VALID SQL") }
            fail("expected the invalid statement to fail")
        } catch (expected: java.sql.SQLException) {
            db.rollback()
        } finally {
            db.autoCommit = true
        }
        assertEquals(before, snapshot())
        assertFalse(columns("credentials").contains("historyCipher"))
    }

    @Test
    fun deletingALoginRemovesItsAppLinksAndDeletingANoteItsVersions() {
        migrate()
        db.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys = ON")
            s.execute("INSERT INTO app_links VALUES ('com.example.app','c1',1)")
            s.execute("INSERT INTO note_versions VALUES ('v1','n1','old','','old body',5)")
            s.execute("DELETE FROM credentials WHERE id = 'c1'")
            s.execute("DELETE FROM notes WHERE id = 'n1'")
        }
        assertEquals(0, count("SELECT COUNT(*) FROM app_links"))
        assertEquals(0, count("SELECT COUNT(*) FROM note_versions"))
    }

    // ---------- helpers ----------

    private fun loadSchema(version: Int): JSONObject {
        val file = File("schemas/com.zero7goat.taz.core.database.TazDatabase/$version.json")
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
                db.createStatement().use { it.execute(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
        }
    }

    private fun seed() {
        db.createStatement().use { s ->
            s.execute("INSERT INTO folders VALUES ('f1','PASSWORDS','Banking',NULL,1000)")
            s.execute("INSERT INTO notes VALUES ('n1',NULL,'Title','Desc','Body',2000,3000,1,0,2,NULL)")
            s.execute("INSERT INTO files VALUES ('x1',NULL,'a.pdf','a.pdf','application/pdf',123,'vault/files/x1','k','d',0,6000,7000,NULL)")
        }
        db.prepareStatement("INSERT INTO credentials VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { p ->
            listOf("c1", "c2").forEachIndexed { index, id ->
                p.setString(1, id); p.setString(2, "f1"); p.setString(3, "Example $index"); p.setString(4, "https://example.test")
                p.setString(5, "example.test")
                p.setBytes(6, byteArrayOf(1, 2, index.toByte())); p.setBytes(7, byteArrayOf(4, 5, index.toByte()))
                p.setBytes(8, byteArrayOf(7, 8, index.toByte())); p.setInt(9, 1); p.setLong(10, 4000); p.setLong(11, 5000)
                p.setNull(12, java.sql.Types.INTEGER); p.setString(13, "login"); p.setBytes(14, byteArrayOf(9, index.toByte()))
                p.executeUpdate()
            }
        }
    }

    private fun columns(table: String): List<String> {
        val names = ArrayList<String>()
        db.createStatement().use { s -> s.executeQuery("PRAGMA table_info(`$table`)").use { rs -> while (rs.next()) names += rs.getString("name") } }
        return names
    }

    private fun count(sql: String): Int = db.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getInt(1) } }

    /** Every pre-existing column of every pre-existing row, as text (BLOBs as hex). */
    private fun snapshot(): Map<String, List<String>> = listOf(
        "folders" to listOf("id", "module", "name", "parentId", "createdAt"),
        "notes" to listOf("id", "title", "description", "body", "createdAt", "updatedAt", "isFavorite", "isPinned", "colorIndex", "deletedAt"),
        "files" to listOf("id", "originalName", "displayName", "mimeType", "sizeBytes", "storedPath", "keywords", "description", "createdAt", "updatedAt"),
        "credentials" to listOf("id", "folderId", "serviceName", "websiteUrl", "domain", "usernameCipher", "passwordCipher", "noteCipher", "isFavorite", "createdAt", "updatedAt", "deletedAt", "category", "fieldsCipher")
    ).associate { (table, cols) ->
        table to db.createStatement().use { s ->
            s.executeQuery("SELECT ${cols.joinToString(",") { "quote(`$it`)" }} FROM `$table` ORDER BY id").use { rs ->
                val rows = ArrayList<String>()
                while (rs.next()) rows += (1..cols.size).joinToString("|") { rs.getString(it) ?: "NULL" }
                rows
            }
        }
    }
}
