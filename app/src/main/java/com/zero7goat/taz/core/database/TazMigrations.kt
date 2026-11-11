package com.zero7goat.taz.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change gets an explicit migration here. Keep the SQL identical to what
 * Room expects for the entity (see the exported JSON files in app/schemas).
 */
object TazMigrations {

    /** Version 2: adds the notes table. */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `notes` (" +
                    "`id` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`body` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`isFavorite` INTEGER NOT NULL, " +
                    "`isPinned` INTEGER NOT NULL, " +
                    "`colorIndex` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
        }
    }

    /**
     * Version 3: notes can live in a folder. Existing notes get a null folderId (top level).
     * If a folder is deleted, its notes are kept and moved to the top level.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `notes` ADD COLUMN `folderId` TEXT " +
                    "REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_folderId` ON `notes` (`folderId`)")
        }
    }

    /**
     * Version 4: adds the credentials table (saved logins). Username, password and notes are
     * stored only as encrypted BLOBs. Notes, folders and everything else are left untouched.
     * If a folder is deleted, its entries are kept and moved to the top level.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `credentials` (" +
                    "`id` TEXT NOT NULL, " +
                    "`folderId` TEXT, " +
                    "`serviceName` TEXT NOT NULL, " +
                    "`websiteUrl` TEXT NOT NULL, " +
                    "`domain` TEXT NOT NULL, " +
                    "`usernameCipher` BLOB NOT NULL, " +
                    "`passwordCipher` BLOB NOT NULL, " +
                    "`noteCipher` BLOB NOT NULL, " +
                    "`isFavorite` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE SET NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_credentials_folderId` ON `credentials` (`folderId`)"
            )
        }
    }

    /**
     * Version 5: adds the files table (metadata of imported files; the files themselves live in
     * private storage, never in the database). Notes, logins and folders are left untouched.
     * If a folder is deleted, its files are kept and moved to the top level.
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `files` (" +
                    "`id` TEXT NOT NULL, " +
                    "`folderId` TEXT, " +
                    "`originalName` TEXT NOT NULL, " +
                    "`displayName` TEXT NOT NULL, " +
                    "`mimeType` TEXT NOT NULL, " +
                    "`sizeBytes` INTEGER NOT NULL, " +
                    "`storedPath` TEXT NOT NULL, " +
                    "`keywords` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`isFavorite` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`folderId`) REFERENCES `folders`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE SET NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_folderId` ON `files` (`folderId`)")
        }
    }

    /**
     * Version 6: the trash. Adds a nullable `deletedAt` column to notes, credentials and files.
     * Every existing row gets NULL, meaning "not in the trash", so nothing about existing data
     * changes. The statements are plain ALTER TABLEs (no table is dropped or rebuilt), so an
     * interrupted migration is rolled back by SQLite's transaction and the old data stays intact.
     */
    val MIGRATION_5_6_SQL: List<String> = listOf(
        "ALTER TABLE `notes` ADD COLUMN `deletedAt` INTEGER",
        "ALTER TABLE `credentials` ADD COLUMN `deletedAt` INTEGER",
        "ALTER TABLE `files` ADD COLUMN `deletedAt` INTEGER"
    )

    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_5_6_SQL.forEach { db.execSQL(it) }
        }
    }

    /**
     * Version 7: tags, categories and custom fields.
     *
     * Only adds: four new, empty tables and two columns on `credentials`. Every existing login gets
     * the category "login" (what every entry was before) and no custom fields (NULL). No existing
     * row, value or ciphertext is read, changed or re-encrypted. Room runs a migration inside one
     * transaction, so if any statement fails nothing is applied and the version stays 6.
     */
    val MIGRATION_6_7_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`normalizedName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_normalizedName` ON `tags` (`normalizedName`)",
        "CREATE TABLE IF NOT EXISTS `item_tags` (`tagId` TEXT NOT NULL, `itemType` TEXT NOT NULL, " +
            "`itemId` TEXT NOT NULL, PRIMARY KEY(`tagId`, `itemType`, `itemId`), " +
            "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_item_tags_itemType_itemId` ON `item_tags` (`itemType`, `itemId`)",
        "CREATE INDEX IF NOT EXISTS `index_item_tags_tagId` ON `item_tags` (`tagId`)",
        "CREATE TABLE IF NOT EXISTS `credential_categories` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`icon` TEXT NOT NULL, `fieldsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `note_fields` (`noteId` TEXT NOT NULL, `fieldsCipher` BLOB NOT NULL, " +
            "PRIMARY KEY(`noteId`), FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "ALTER TABLE `credentials` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'login'",
        "ALTER TABLE `credentials` ADD COLUMN `fieldsCipher` BLOB"
    )

    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_6_7_SQL.forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
    )
}
