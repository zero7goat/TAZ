package com.zero7goat.taz.core.database

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Runs the real version 5 to 6 migration SQL against an in-memory SQLite database built from the
 * exported version 5 schema, with synthetic data. Proves that the migration keeps every existing
 * row and value, adds exactly the columns Room expects for version 6, and is all-or-nothing.
 */
class TrashMigrationTest {

    private lateinit var db: Connection

    @Before
    fun setUp() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        createSchema(5)
        seedVersion5()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun existingRowsAndValuesSurviveUnchanged() {
        val before = snapshot()
        TazMigrations.MIGRATION_5_6_SQL.forEach { db.createStatement().use { s -> s.execute(it) } }
        val after = snapshot()
        assertEquals("every original column of every row is identical", before, after)
    }

    @Test
    fun everyExistingItemStartsOutOfTheTrash() {
        TazMigrations.MIGRATION_5_6_SQL.forEach { db.createStatement().use { s -> s.execute(it) } }
        for (table in listOf("notes", "credentials", "files")) {
            assertEquals(table, 0, count("SELECT COUNT(*) FROM $table WHERE deletedAt IS NOT NULL"))
            assertEquals(table, count("SELECT COUNT(*) FROM $table"), count("SELECT COUNT(*) FROM $table WHERE deletedAt IS NULL"))
        }
    }

    @Test
    fun tablesMatchTheExportedVersion6Schema() {
        TazMigrations.MIGRATION_5_6_SQL.forEach { db.createStatement().use { s -> s.execute(it) } }
        val schema = loadSchema(6)
        val entities = schema.getJSONObject("database").getJSONArray("entities")
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
    fun migrationOnlyAddsColumnsAndTouchesNoOtherTable() {
        val folderBefore = dump("SELECT * FROM folders ORDER BY id")
        TazMigrations.MIGRATION_5_6_SQL.forEach { db.createStatement().use { s -> s.execute(it) } }
        assertEquals(folderBefore, dump("SELECT * FROM folders ORDER BY id"))
        assertTrue(TazMigrations.MIGRATION_5_6_SQL.all { it.startsWith("ALTER TABLE") && it.contains("ADD COLUMN") })
        val destructive = Regex("\\b(DROP|DELETE|UPDATE|TRUNCATE)\\b", RegexOption.IGNORE_CASE)
        assertFalse(TazMigrations.MIGRATION_5_6_SQL.any { destructive.containsMatchIn(it) })
    }

    @Test
    fun interruptedMigrationLeavesTheOldDatabaseUntouched() {
        val before = snapshot()
        db.autoCommit = false
        try {
            db.createStatement().use { it.execute(TazMigrations.MIGRATION_5_6_SQL[0]) }
            db.createStatement().use { it.execute(TazMigrations.MIGRATION_5_6_SQL[1]) }
            db.createStatement().use { it.execute("THIS IS NOT VALID SQL") } // simulated failure part-way
            fail("expected the invalid statement to fail")
        } catch (expected: java.sql.SQLException) {
            db.rollback()
        } finally {
            db.autoCommit = true
        }
        assertEquals("nothing changed after the rollback", before, snapshot())
        assertFalse(columns("notes").contains("deletedAt"))
        assertFalse(columns("credentials").contains("deletedAt"))
    }

    @Test
    fun migrationListIsRegisteredAndContinuousFrom1To6() {
        val versions = TazMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6), versions)
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
            val indices = entity.optJSONArray("indices")
            if (indices != null) {
                for (j in 0 until indices.length()) {
                    db.createStatement().use {
                        it.execute(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
            }
        }
    }

    private fun seedVersion5() {
        db.createStatement().use { s ->
            s.execute("INSERT INTO folders VALUES ('f1','notes','Work',NULL,1000)")
            s.execute("INSERT INTO folders VALUES ('f2','notes','Sub','f1',1001)")
            s.execute("INSERT INTO notes (id,folderId,title,description,body,createdAt,updatedAt,isFavorite,isPinned,colorIndex) VALUES ('n1','f1','Title','Desc','Body text',2000,3000,1,0,2)")
            s.execute("INSERT INTO notes (id,folderId,title,description,body,createdAt,updatedAt,isFavorite,isPinned,colorIndex) VALUES ('n2',NULL,'','','',2001,3001,0,1,0)")
        }
        db.prepareStatement(
            "INSERT INTO credentials (id,folderId,serviceName,websiteUrl,domain,usernameCipher,passwordCipher,noteCipher,isFavorite,createdAt,updatedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
        ).use { p ->
            p.setString(1, "c1"); p.setString(2, "f1"); p.setString(3, "Example"); p.setString(4, "https://example.test")
            p.setString(5, "example.test"); p.setBytes(6, byteArrayOf(1, 2, 3)); p.setBytes(7, byteArrayOf(4, 5, 6))
            p.setBytes(8, byteArrayOf(7, 8, 9)); p.setInt(9, 1); p.setLong(10, 4000); p.setLong(11, 5000)
            p.executeUpdate()
        }
        db.createStatement().use { s ->
            s.execute("INSERT INTO files (id,folderId,originalName,displayName,mimeType,sizeBytes,storedPath,keywords,description,isFavorite,createdAt,updatedAt) VALUES ('x1','f1','a.pdf','a.pdf','application/pdf',123,'vault/files/x1','k1,k2','d',0,6000,7000)")
        }
    }

    private fun columns(table: String): List<String> {
        val names = ArrayList<String>()
        db.createStatement().use { s ->
            s.executeQuery("PRAGMA table_info(`$table`)").use { rs -> while (rs.next()) names.add(rs.getString("name")) }
        }
        return names
    }

    private fun count(sql: String): Int =
        db.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) } }

    /** Every row of every table, restricted to the columns that exist in version 5, as text. */
    private fun snapshot(): Map<String, List<String>> {
        val v5Columns = mapOf(
            "folders" to listOf("id", "module", "name", "parentId", "createdAt"),
            "notes" to listOf("id", "folderId", "title", "description", "body", "createdAt", "updatedAt", "isFavorite", "isPinned", "colorIndex"),
            "credentials" to listOf("id", "folderId", "serviceName", "websiteUrl", "domain", "usernameCipher", "passwordCipher", "noteCipher", "isFavorite", "createdAt", "updatedAt"),
            "files" to listOf("id", "folderId", "originalName", "displayName", "mimeType", "sizeBytes", "storedPath", "keywords", "description", "isFavorite", "createdAt", "updatedAt")
        )
        return v5Columns.mapValues { (table, cols) ->
            assertNotNull(table)
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
