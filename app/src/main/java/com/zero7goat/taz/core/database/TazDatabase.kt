package com.zero7goat.taz.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.zero7goat.taz.features.files.data.FileDao
import com.zero7goat.taz.features.files.data.FileEntity
import com.zero7goat.taz.features.notes.data.NoteDao
import com.zero7goat.taz.features.notes.data.NoteEntity
import com.zero7goat.taz.features.passwords.data.CredentialDao
import com.zero7goat.taz.features.passwords.data.CredentialEntity

/**
 * TAZ's single Room database. Each schema change bumps the version and adds an explicit
 * migration in [TazMigrations]. Destructive fallback is deliberately never enabled.
 */
@Database(
    entities = [FolderEntity::class, NoteEntity::class, CredentialEntity::class, FileEntity::class],
    version = 6,
    exportSchema = true
)
abstract class TazDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao

    abstract fun noteDao(): NoteDao

    abstract fun credentialDao(): CredentialDao

    abstract fun fileDao(): FileDao

    companion object {
        // DO NOT RENAME: existing user data depends on this exact value (see DataIdentityTest).
        const val DATABASE_NAME = "taz.db"

        @Volatile
        private var instance: TazDatabase? = null

        /** The one shared database instance for the app process. */
        fun getInstance(context: Context): TazDatabase =
            instance ?: synchronized(this) {
                instance ?: create(context).also { instance = it }
            }

        /** Closes the shared instance and forgets it. Used only when all data is erased. */
        fun closeAndForget() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        fun create(context: Context): TazDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TazDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(*TazMigrations.ALL)
                .build()
    }
}
