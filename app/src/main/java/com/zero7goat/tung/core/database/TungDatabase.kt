package com.zero7goat.tung.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.zero7goat.tung.features.files.data.FileDao
import com.zero7goat.tung.features.files.data.FileEntity
import com.zero7goat.tung.features.notes.data.NoteDao
import com.zero7goat.tung.features.notes.data.NoteEntity
import com.zero7goat.tung.features.passwords.data.CredentialDao
import com.zero7goat.tung.features.passwords.data.CredentialEntity

/**
 * TUNG's single Room database. Each schema change bumps the version and adds an explicit
 * migration in [TungMigrations]. Destructive fallback is deliberately never enabled.
 */
@Database(
    entities = [FolderEntity::class, NoteEntity::class, CredentialEntity::class, FileEntity::class],
    version = 5,
    exportSchema = true
)
abstract class TungDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao

    abstract fun noteDao(): NoteDao

    abstract fun credentialDao(): CredentialDao

    abstract fun fileDao(): FileDao

    companion object {
        const val DATABASE_NAME = "tung.db"

        @Volatile
        private var instance: TungDatabase? = null

        /** The one shared database instance for the app process. */
        fun getInstance(context: Context): TungDatabase =
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

        fun create(context: Context): TungDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TungDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(*TungMigrations.ALL)
                .build()
    }
}
