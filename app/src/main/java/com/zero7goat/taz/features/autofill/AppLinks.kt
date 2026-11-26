package com.zero7goat.taz.features.autofill

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import com.zero7goat.taz.features.passwords.data.CredentialEntity

/**
 * Remembers that a login was picked for an app, so autofill suggests it there next time. Holds
 * only the app's package name and the login's id: no username or password. Removed with the login.
 */
@Entity(
    tableName = "app_links",
    primaryKeys = ["packageName", "credentialId"],
    foreignKeys = [
        ForeignKey(
            entity = CredentialEntity::class,
            parentColumns = ["id"],
            childColumns = ["credentialId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["credentialId"])]
)
data class AppLinkEntity(
    val packageName: String,
    val credentialId: String,
    val createdAt: Long
)

@Dao
interface AppLinkDao {
    @Upsert
    suspend fun upsert(link: AppLinkEntity)

    @Query("SELECT credentialId FROM app_links WHERE packageName = :packageName")
    suspend fun credentialIdsFor(packageName: String): List<String>

    @Query("SELECT packageName FROM app_links WHERE credentialId = :credentialId ORDER BY packageName")
    suspend fun packagesFor(credentialId: String): List<String>

    @Query("DELETE FROM app_links WHERE packageName = :packageName AND credentialId = :credentialId")
    suspend fun delete(packageName: String, credentialId: String)

    @Query("SELECT * FROM app_links")
    suspend fun getAll(): List<AppLinkEntity>
}
