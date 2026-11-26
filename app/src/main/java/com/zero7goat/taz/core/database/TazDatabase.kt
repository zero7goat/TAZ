package com.zero7goat.taz.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.zero7goat.taz.core.security.VaultMode
import com.zero7goat.taz.core.tags.ItemTagEntity
import com.zero7goat.taz.core.tags.TagDao
import com.zero7goat.taz.core.tags.TagEntity
import com.zero7goat.taz.features.notes.data.NoteFieldsEntity
import com.zero7goat.taz.features.passwords.data.CategoryDao
import com.zero7goat.taz.features.passwords.data.CategoryEntity
import com.zero7goat.taz.features.autofill.AppLinkDao
import com.zero7goat.taz.features.autofill.AppLinkEntity
import com.zero7goat.taz.features.passkeys.PasskeyDao
import com.zero7goat.taz.features.passkeys.PasskeyEntity
import com.zero7goat.taz.features.notes.data.NoteHistoryDao
import com.zero7goat.taz.features.notes.data.NoteTemplateEntity
import com.zero7goat.taz.features.notes.data.NoteVersionEntity
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
    entities = [
        FolderEntity::class, NoteEntity::class, CredentialEntity::class, FileEntity::class,
        TagEntity::class, ItemTagEntity::class, CategoryEntity::class, NoteFieldsEntity::class,
        AppLinkEntity::class, NoteVersionEntity::class, NoteTemplateEntity::class, PasskeyEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class TazDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao

    abstract fun tagDao(): TagDao

    abstract fun categoryDao(): CategoryDao

    abstract fun noteDao(): NoteDao

    abstract fun credentialDao(): CredentialDao

    abstract fun fileDao(): FileDao

    abstract fun appLinkDao(): AppLinkDao

    abstract fun noteHistoryDao(): NoteHistoryDao

    abstract fun passkeyDao(): PasskeyDao

    companion object {
        // DO NOT RENAME: existing user data depends on this exact value (see DataIdentityTest).
        const val DATABASE_NAME = "taz.db"

        /** The empty decoy vault the duress passcode opens. Never holds real data. */
        const val DECOY_DATABASE_NAME = "taz_decoy.db"

        @Volatile
        private var instance: TazDatabase? = null

        @Volatile
        private var decoyInstance: TazDatabase? = null

        /**
         * The shared database of the vault being shown (see VaultMode). Both stay open once used,
         * so switching vaults never closes a database that some screen is still reading.
         */
        fun getInstance(context: Context): TazDatabase =
            if (VaultMode.isDecoy) {
                decoyInstance ?: synchronized(this) {
                    decoyInstance ?: create(context, DECOY_DATABASE_NAME).also { decoyInstance = it }
                }
            } else {
                instance ?: synchronized(this) {
                    instance ?: create(context, DATABASE_NAME).also { instance = it }
                }
            }

        /** Closes both instances and forgets them. Used only when all data is erased. */
        fun closeAndForget() {
            synchronized(this) {
                instance?.close()
                instance = null
                decoyInstance?.close()
                decoyInstance = null
            }
        }

        fun create(context: Context, name: String = DATABASE_NAME): TazDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TazDatabase::class.java,
                name
            )
                .addMigrations(*TazMigrations.ALL)
                .build()
    }
}
