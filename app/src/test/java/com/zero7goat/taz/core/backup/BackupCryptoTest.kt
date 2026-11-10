package com.zero7goat.taz.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {

    // Real backups use 600,000 iterations; tests use few so they run quickly.
    private val testIterations = 1_000
    private val passphrase = "correct horse battery staple"

    private fun header(chunkSize: Int = 1024) = BackupHeader(
        iterations = testIterations,
        salt = ByteArray(16).also { SecureRandom().nextBytes(it) },
        noncePrefix = ByteArray(8).also { SecureRandom().nextBytes(it) },
        chunkSize = chunkSize
    )

    private fun encrypt(plain: ByteArray, header: BackupHeader = header()): ByteArray {
        val out = ByteArrayOutputStream()
        val key = BackupKeys.derive(passphrase.toCharArray(), header)
        EncryptingOutputStream(out, key, header).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun decrypt(file: ByteArray, secret: String = passphrase): ByteArray {
        val input = ByteArrayInputStream(file)
        val header = BackupHeader.read(input, minIterations = 1)
        val key = BackupKeys.derive(secret.toCharArray(), header)
        return DecryptingInputStream(input, key, header).readBytes()
    }

    private fun assertFails(expected: BackupError, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (e: BackupException) {
            assertEquals(expected, e.error)
        }
    }

    private fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

    @Test
    fun roundTripsEmptySmallExactAndMultiChunkData() {
        listOf(0, 1, 1023, 1024, 1025, 5000, 10 * 1024).forEach { size ->
            val plain = random(size)
            assertArrayEquals("size $size", plain, decrypt(encrypt(plain)))
        }
    }

    @Test
    fun ciphertextDoesNotContainThePlaintext() {
        val plain = "password=hunter2-unique-marker".repeat(50).toByteArray()
        val file = String(encrypt(plain), Charsets.ISO_8859_1)
        assertFalse(file.contains("hunter2-unique-marker"))
    }

    @Test
    fun wrongPassphraseIsReported() {
        val file = encrypt("secret".toByteArray())
        assertFails(BackupError.WRONG_PASSPHRASE) { decrypt(file, "wrong passphrase!") }
    }

    @Test
    fun passphraseIsUnicodeNormalized() {
        val composed = "café-passphrase"
        val decomposed = "café-passphrase"
        val h = header()
        val out = ByteArrayOutputStream()
        EncryptingOutputStream(out, BackupKeys.derive(composed.toCharArray(), h), h).use { it.write(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), decrypt(out.toByteArray(), decomposed))
    }

    @Test
    fun tamperingInALaterChunkIsDetected() {
        val file = encrypt(random(4000))
        file[file.size - 20] = (file[file.size - 20].toInt() xor 1).toByte()
        assertFails(BackupError.DAMAGED) { decrypt(file) }
    }

    @Test
    fun tamperingWithTheHeaderIsDetected() {
        val file = encrypt(random(100))
        // Change one byte of the salt: the key and the authenticated header no longer match.
        file[16] = (file[16].toInt() xor 1).toByte()
        assertFails(BackupError.WRONG_PASSPHRASE) { decrypt(file) }
    }

    @Test
    fun aFileCutShortIsIncomplete() {
        val file = encrypt(random(5000))
        // Cut inside the last chunk, and exactly at a chunk boundary (last chunk missing).
        assertFails(BackupError.INCOMPLETE) { decrypt(file.copyOf(file.size - 10)) }
        val headerSize = header().encode().size
        val firstChunkEnd = headerSize + 5 + 1024 + 16
        assertFails(BackupError.INCOMPLETE) { decrypt(file.copyOf(firstChunkEnd)) }
    }

    @Test
    fun dataAfterTheLastChunkIsRejected() {
        val file = encrypt(random(100)) + byteArrayOf(0)
        assertFails(BackupError.DAMAGED) { decrypt(file) }
    }

    @Test
    fun droppingTheLastChunkFlagIsDetected() {
        val file = encrypt(random(100))
        val headerSize = header().encode().size
        file[headerSize] = 0 // claims "not the last chunk"; the flag is authenticated
        assertFails(BackupError.WRONG_PASSPHRASE) { decrypt(file) }
    }

    @Test
    fun otherFilesAreNotBackups() {
        assertFails(BackupError.NOT_A_BACKUP) { decrypt("PK\u0003\u0004 just a zip".toByteArray()) }
        assertFails(BackupError.NOT_A_BACKUP) { decrypt(ByteArray(3)) }
    }

    @Test
    fun unsupportedVersionIsRejected() {
        val file = encrypt(random(10))
        file[8] = 2
        assertFails(BackupError.UNSUPPORTED_VERSION) { decrypt(file) }
    }

    @Test
    fun headersWithTooFewIterationsAreRejectedByDefault() {
        val file = encrypt(random(10))
        assertFails(BackupError.UNSUPPORTED_VERSION) { BackupHeader.read(ByteArrayInputStream(file)) }
    }

    @Test
    fun everyBackupGetsAFreshSaltAndNoncePrefix() {
        val a = BackupHeader.create()
        val b = BackupHeader.create()
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.noncePrefix.contentEquals(b.noncePrefix))
        assertEquals(BackupHeader.DEFAULT_ITERATIONS, a.iterations)
        assertTrue(a.iterations >= 600_000)
        assertEquals(16, a.salt.size)
    }

    @Test
    fun sameDataEncryptsDifferentlyEachTime() {
        val plain = random(100)
        assertFalse(encrypt(plain).contentEquals(encrypt(plain)))
    }
}
