package com.zero7goat.tung.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasscodeHasherTest {

    // Few iterations keep the tests fast; the algorithm is identical.
    private val iterations = 1_000

    @Test
    fun sameInputGivesTheSameHash() {
        val salt = ByteArray(16) { it.toByte() }
        assertArrayEquals(
            PasscodeHasher.hash("123456".toCharArray(), salt, iterations),
            PasscodeHasher.hash("123456".toCharArray(), salt, iterations)
        )
    }

    @Test
    fun hashIs32BytesAndNeverTheInput() {
        val salt = PasscodeHasher.newSalt()
        val hash = PasscodeHasher.hash("123456".toCharArray(), salt, iterations)
        assertEquals(32, hash.size)
        assertFalse(String(hash, Charsets.ISO_8859_1).contains("123456"))
    }

    @Test
    fun differentSaltsGiveDifferentHashes() {
        val a = PasscodeHasher.hash("123456".toCharArray(), ByteArray(16) { 1 }, iterations)
        val b = PasscodeHasher.hash("123456".toCharArray(), ByteArray(16) { 2 }, iterations)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun differentPasscodesGiveDifferentHashes() {
        val salt = ByteArray(16) { 7 }
        assertFalse(
            PasscodeHasher.hash("123456".toCharArray(), salt, iterations)
                .contentEquals(PasscodeHasher.hash("123457".toCharArray(), salt, iterations))
        )
    }

    @Test
    fun iterationCountChangesTheHash() {
        val salt = ByteArray(16) { 7 }
        assertFalse(
            PasscodeHasher.hash("1234".toCharArray(), salt, 1_000)
                .contentEquals(PasscodeHasher.hash("1234".toCharArray(), salt, 2_000))
        )
    }

    @Test
    fun matchesAcceptsTheRightPasscodeOnly() {
        val salt = PasscodeHasher.newSalt()
        val stored = PasscodeHasher.hash("2468".toCharArray(), salt, iterations)
        assertTrue(PasscodeHasher.matches("2468".toCharArray(), salt, iterations, stored))
        assertFalse(PasscodeHasher.matches("2469".toCharArray(), salt, iterations, stored))
        assertFalse(PasscodeHasher.matches("".toCharArray(), salt, iterations, stored))
    }

    @Test
    fun saltsAreRandomAndSixteenBytes() {
        val a = PasscodeHasher.newSalt()
        val b = PasscodeHasher.newSalt()
        assertEquals(16, a.size)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun knownPbkdf2Sha256Vector() {
        // RFC 7914 section 11 / widely used test vector: P="passwd", S="salt", c=1, dkLen=64 starts with 55ac046e...
        // Here dkLen is 32 bytes, so the first 32 bytes of the 64-byte vector are expected.
        val hash = PasscodeHasher.hash("passwd".toCharArray(), "salt".toByteArray(), 1)
        val hex = hash.joinToString("") { "%02x".format(it) }
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", hex)
    }
}
