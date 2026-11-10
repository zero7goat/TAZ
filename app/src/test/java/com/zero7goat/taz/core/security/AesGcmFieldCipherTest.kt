package com.zero7goat.taz.core.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class AesGcmFieldCipherTest {

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val cipher = AesGcmFieldCipher { key }
    private val aad = "taz.credential.v1|id-1|password"

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected FieldCipherException")
        } catch (e: FieldCipherException) {
            // expected
        }
    }

    @Test
    fun roundTripsUnicodeText() {
        val text = "p\u00e4ssw\u00f6rd \u2713 123 !@#"
        assertEquals(text, cipher.decrypt(cipher.encrypt(text, aad), aad))
    }

    @Test
    fun roundTripsEmptyText() {
        assertEquals("", cipher.decrypt(cipher.encrypt("", aad), aad))
    }

    @Test
    fun blobStartsWithFormatVersionOne() {
        assertEquals(1.toByte(), cipher.encrypt("x", aad)[0])
    }

    @Test
    fun blobLengthIsVersionPlusNoncePlusTextPlusTag() {
        val blob = cipher.encrypt("abcde", aad)
        assertEquals(1 + 12 + 5 + 16, blob.size)
    }

    @Test
    fun usesAFreshNonceEveryTime() {
        val first = cipher.encrypt("same", aad)
        val second = cipher.encrypt("same", aad)
        assertFalse(first.contentEquals(second))
        assertNotEquals(first.copyOfRange(1, 13).toList(), second.copyOfRange(1, 13).toList())
    }

    @Test
    fun ciphertextDoesNotContainThePlaintext() {
        val secret = "correct-horse-battery-staple"
        val blob = cipher.encrypt(secret, aad)
        val needle = secret.toByteArray()
        val found = (0..blob.size - needle.size).any { start ->
            needle.indices.all { blob[start + it] == needle[it] }
        }
        assertFalse(found)
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val blob = cipher.encrypt("secret", aad)
        blob[14] = (blob[14].toInt() xor 1).toByte()
        assertRejected { cipher.decrypt(blob, aad) }
    }

    @Test
    fun rejectsTamperedTag() {
        val blob = cipher.encrypt("secret", aad)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertRejected { cipher.decrypt(blob, aad) }
    }

    @Test
    fun rejectsDifferentField() {
        val blob = cipher.encrypt("secret", aad)
        assertRejected { cipher.decrypt(blob, "taz.credential.v1|id-1|username") }
    }

    @Test
    fun rejectsDifferentRow() {
        val blob = cipher.encrypt("secret", aad)
        assertRejected { cipher.decrypt(blob, "taz.credential.v1|id-2|password") }
    }

    @Test
    fun rejectsADifferentKey() {
        val blob = cipher.encrypt("secret", aad)
        val other = AesGcmFieldCipher { newKey() }
        assertRejected { other.decrypt(blob, aad) }
    }

    @Test
    fun rejectsUnknownFormatVersion() {
        val blob = cipher.encrypt("secret", aad)
        blob[0] = 2
        assertRejected { cipher.decrypt(blob, aad) }
    }

    @Test
    fun rejectsTooShortData() {
        assertRejected { cipher.decrypt(ByteArray(10), aad) }
        assertRejected { cipher.decrypt(ByteArray(0), aad) }
    }

    @Test
    fun reportsAMissingKeyAsACipherError() {
        val broken = AesGcmFieldCipher { throw IllegalStateException("no key") }
        assertRejected { broken.encrypt("x", aad) }
    }

    @Test
    fun doesNotModifyTheStoredBlobWhenDecrypting() {
        val blob = cipher.encrypt("secret", aad)
        val copy = blob.copyOf()
        cipher.decrypt(blob, aad)
        assertArrayEquals(copy, blob)
    }
}
