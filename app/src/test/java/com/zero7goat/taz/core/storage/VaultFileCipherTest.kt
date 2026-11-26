package com.zero7goat.taz.core.storage

import com.zero7goat.taz.core.security.AesGcmFieldCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Random
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultFileCipherTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = VaultFileCipher(AesGcmFieldCipher { key })
    private val dir: File = Files.createTempDirectory("taz-files").toFile()

    private fun encryptToFile(data: ByteArray, id: String): File {
        val file = File(dir, id)
        file.outputStream().use { out -> cipher.encrypt(data.inputStream(), out, id) }
        return file
    }

    private fun readAll(file: File, id: String): ByteArray = cipher.open(file, id).use { it.readBytes() }

    @Test
    fun roundTripsFilesOfEverySizeIncludingChunkBoundaries() {
        val random = Random(42)
        for (size in listOf(0, 1, 100, VaultFileCipher.CHUNK - 1, VaultFileCipher.CHUNK, VaultFileCipher.CHUNK + 1, 3 * VaultFileCipher.CHUNK + 17)) {
            val data = ByteArray(size).also { random.nextBytes(it) }
            val file = encryptToFile(data, "file-$size")
            assertTrue(cipher.isEncrypted(file))
            assertArrayEquals("size $size", data, readAll(file, "file-$size"))
            assertEquals(size.toLong(), cipher.plainLength(file))
        }
    }

    @Test
    fun plainFilesFromBeforeEncryptionAreReadAsTheyAre() {
        val plain = File(dir, "old").also { it.writeText("hello from 1.2") }
        assertFalse(cipher.isEncrypted(plain))
        assertEquals("hello from 1.2", String(readAll(plain, "old")))
        assertEquals(plain.length(), cipher.plainLength(plain))
    }

    @Test
    fun aFileCannotBeOpenedUnderAnotherFilesId() {
        val file = encryptToFile("secret".toByteArray(), "a")
        try {
            readAll(file, "b")
            fail("opened with the wrong id")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun cuttingOffTheLastChunkOrChangingAByteIsDetected() {
        val data = ByteArray(2 * VaultFileCipher.CHUNK + 10) { it.toByte() }
        val file = encryptToFile(data, "t")
        val bytes = file.readBytes()

        // Drop the final chunk: the one before it is not marked as last, so this must fail.
        val lastChunkSize = 4 + 10 + 16
        File(dir, "cut").writeBytes(bytes.copyOf(bytes.size - lastChunkSize))
        expectFailure { readAll(File(dir, "cut"), "t") }

        val flipped = bytes.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        File(dir, "flip").writeBytes(flipped)
        expectFailure { readAll(File(dir, "flip"), "t") }
    }

    @Test
    fun theStoredBytesAreNotThePlainText() {
        val data = "a very recognisable plain text ".repeat(50).toByteArray()
        val file = encryptToFile(data, "x")
        val stored = ByteArrayOutputStream().also { it.write(file.readBytes()) }.toString("ISO-8859-1")
        assertFalse(stored.contains("recognisable"))
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("damage was not detected")
        } catch (e: IOException) {
            // expected
        }
    }
}
