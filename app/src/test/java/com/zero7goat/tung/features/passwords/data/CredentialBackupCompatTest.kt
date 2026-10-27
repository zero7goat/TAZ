package com.zero7goat.tung.features.passwords.data

import com.zero7goat.tung.core.security.AesGcmFieldCipher
import com.zero7goat.tung.features.passwords.domain.CredentialDetail
import com.zero7goat.tung.features.passwords.domain.CredentialInput
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backups decrypt logins with the device key and restore them by encrypting again with the
 * current installation's key, in the existing format and with the existing associated data.
 */
class CredentialBackupCompatTest {

    private class FakeDao : CredentialDao {
        val rows = LinkedHashMap<String, CredentialEntity>()
        override suspend fun upsert(entity: CredentialEntity) { rows[entity.id] = entity }
        override suspend fun getById(id: String) = rows[id]
        override fun observeListRows(): Flow<List<CredentialListRow>> = flowOf(emptyList())
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun setFolder(id: String, folderId: String?) = Unit
        override suspend fun getAll() = rows.values.toList()
        override suspend fun insertAll(entities: List<CredentialEntity>) = entities.forEach { rows[it.id] = it }
        override suspend fun deleteAll() = rows.clear()
    }

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val detail = CredentialDetail(
        id = "id-1", folderId = null, serviceName = "Mail", username = "alice@example.com",
        password = "päss ✓", websiteUrl = "https://mail.example.com/", notes = "recovery: 1234",
        isFavorite = true, createdAt = 100L, updatedAt = 200L
    )

    @Test
    fun restoredLoginsAreReadableThroughTheNormalPath() = runBlocking {
        val oldDevice = CredentialRepository(FakeDao(), AesGcmFieldCipher(newKey().let { k -> { k } }))
        val newDao = FakeDao()
        val newDevice = CredentialRepository(newDao, AesGcmFieldCipher(newKey().let { k -> { k } }))

        val entity = newDevice.encryptForRestore(detail)
        newDao.insertAll(listOf(entity))
        val read = newDevice.getDetail("id-1") as DetailResult.Found
        assertEquals(detail.username, read.detail.username)
        assertEquals(detail.password, read.detail.password)
        assertEquals(detail.notes, read.detail.notes)
        assertEquals(100L, read.detail.createdAt)
        assertEquals(200L, read.detail.updatedAt)
        assertEquals("mail.example.com", entity.domain)

        // Ciphertext from another key (the old device) cannot be read here: keys are never shared.
        val foreign = oldDevice.encryptForRestore(detail)
        newDao.insertAll(listOf(foreign))
        assertEquals(DetailResult.Unreadable, newDevice.getDetail("id-1"))
    }

    @Test
    fun restoreUsesTheSameFormatAsSave() = runBlocking {
        val key = newKey()
        val cipher = AesGcmFieldCipher { key }
        val dao = FakeDao()
        val repository = CredentialRepository(dao, cipher, now = { 200L })
        repository.save(
            CredentialInput(detail.id, null, detail.serviceName, detail.username, detail.password, detail.websiteUrl, detail.notes, true, 100L)
        )
        val saved = dao.rows.getValue("id-1")
        val restored = repository.encryptForRestore(detail)
        // Both decrypt with the existing associated data scheme; the format version byte is the same.
        assertEquals(saved.passwordCipher[0], restored.passwordCipher[0])
        assertEquals(detail.password, cipher.decrypt(restored.passwordCipher, "tung.credential.v1|id-1|password"))
        assertEquals(detail.username, cipher.decrypt(restored.usernameCipher, "tung.credential.v1|id-1|username"))
        assertEquals(detail.notes, cipher.decrypt(restored.noteCipher, "tung.credential.v1|id-1|notes"))
    }

    @Test
    fun unreadableLoginsAreSkippedAndNeverOverwritten() = runBlocking {
        val key = newKey()
        val dao = FakeDao()
        val repository = CredentialRepository(dao, AesGcmFieldCipher { key })
        dao.insertAll(listOf(repository.encryptForRestore(detail)))
        val damaged = repository.encryptForRestore(detail.copy(id = "id-2")).let {
            it.copy(passwordCipher = it.passwordCipher.copyOf().also { bytes -> bytes[20] = (bytes[20].toInt() xor 1).toByte() })
        }
        dao.insertAll(listOf(damaged))
        val before = dao.rows.getValue("id-2").passwordCipher.copyOf()

        val (details, unreadable) = repository.decryptAllForBackup()
        assertEquals(listOf("id-1"), details.map { it.id })
        assertEquals(1, unreadable)
        assertArrayEquals(before, dao.rows.getValue("id-2").passwordCipher)
        assertTrue(details.single().toString().contains("hidden"))
    }
}
