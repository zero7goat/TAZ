package com.zero7goat.taz.features.passwords.presentation

import com.zero7goat.taz.core.security.AesGcmFieldCipher
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic data only. Covers keeping unsaved login text across process recreation. */
class DraftSnapshotTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmFieldCipher { key }
    private val fields = DraftFields(
        serviceName = "Example Social",
        username = "someone@example.test",
        password = "Synthetic-Pass-123!",
        websiteUrl = "https://social.example.test",
        notes = "recovery hint"
    )

    @Test
    fun roundTripsEveryField() {
        val restored = DraftSnapshot.decode(DraftSnapshot.encode(fields, "id-1", cipher), "id-1", cipher)
        assertNotNull(restored)
        assertEquals(fields.serviceName, restored!!.serviceName)
        assertEquals(fields.username, restored.username)
        assertEquals(fields.password, restored.password)
        assertEquals(fields.websiteUrl, restored.websiteUrl)
        assertEquals(fields.notes, restored.notes)
    }

    @Test
    fun keepsAFormWhosePasswordIsStillEmpty() {
        val partial = DraftFields("Example Social", "someone@example.test", "", "https://social.example.test", "")
        val restored = DraftSnapshot.decode(DraftSnapshot.encode(partial, "id-1", cipher), "id-1", cipher)!!
        assertEquals("Example Social", restored.serviceName)
        assertEquals("", restored.password)
    }

    @Test
    fun savedBytesNeverContainThePlainText() {
        val blob = String(DraftSnapshot.encode(fields, "id-1", cipher), Charsets.ISO_8859_1)
        assertFalse(blob.contains(fields.password))
        assertFalse(blob.contains(fields.username))
        assertFalse(blob.contains(fields.serviceName))
    }

    @Test
    fun aDraftCanOnlyBeRestoredIntoTheLoginItCameFrom() {
        val blob = DraftSnapshot.encode(fields, "id-1", cipher)
        assertNull(DraftSnapshot.decode(blob, "id-2", cipher))
    }

    @Test
    fun damagedOrForeignBlobsAreRejectedNotGuessed() {
        val blob = DraftSnapshot.encode(fields, "id-1", cipher)
        val damaged = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(DraftSnapshot.decode(damaged, "id-1", cipher))
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(DraftSnapshot.decode(blob, "id-1", AesGcmFieldCipher { otherKey }))
        assertNull(DraftSnapshot.decode(ByteArray(3), "id-1", cipher))
    }

    @Test
    fun toStringHidesTheText() {
        assertFalse(fields.toString().contains(fields.password))
    }
}
