package com.zero7goat.tung.core.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Portable backup encryption. Pure JVM code (no Android classes) so it can be unit tested.
 *
 * A backup file is encrypted with a key derived from a passphrase the user types. It does NOT use
 * the Android Keystore key that protects logins on the device, so a backup can be restored after a
 * reinstall or on another device. The passphrase is never stored.
 *
 * File layout (all integers big-endian):
 *
 *     header (authenticated, not secret)
 *       8  bytes  magic "TAZBACKP"
 *       1  byte   format version (1)
 *       1  byte   key derivation id (1 = PBKDF2-HMAC-SHA256)
 *       4  bytes  PBKDF2 iteration count
 *       1  byte   salt length (16)
 *       n  bytes  random salt
 *       8  bytes  random nonce prefix
 *       4  bytes  plaintext chunk size (65536)
 *     chunks, one after another
 *       1  byte   1 for the last chunk, otherwise 0
 *       4  bytes  ciphertext length (chunk plaintext + 16 byte tag)
 *       n  bytes  AES-256-GCM ciphertext and tag
 *
 * Every chunk is encrypted with AES-256-GCM using:
 *   - key   = PBKDF2-HMAC-SHA256(passphrase normalized to Unicode NFC, salt, iterations, 256 bits)
 *   - nonce = nonce prefix (8 bytes) + chunk counter (4 bytes): unique per chunk; the key itself
 *             is unique per backup because the salt is random
 *   - AAD   = the complete header + chunk counter + last-chunk flag
 * so the header cannot be altered, chunks cannot be reordered, dropped or repeated, and a file
 * that was cut short is detected (the last chunk must carry the flag and nothing may follow it).
 * The decrypted content is a ZIP archive (see BackupArchive).
 */

/** Why a backup could not be created or restored. Never carries any user data. */
enum class BackupError {
    NOT_A_BACKUP,
    UNSUPPORTED_VERSION,
    /** The first chunk failed to authenticate: almost always a wrong passphrase. */
    WRONG_PASSPHRASE,
    DAMAGED,
    INCOMPLETE,
    INVALID_CONTENT,
    TOO_LARGE,
    NOT_ENOUGH_STORAGE,
    CANNOT_READ,
    CANNOT_WRITE,
    /** Some logins cannot be decrypted and the user did not agree to leave them out. */
    UNREADABLE_LOGINS,
    FAILED
}

/** A backup failure. The message is the [error] name only, never data or passphrases. */
class BackupException(val error: BackupError) : IOException(error.name)

class BackupHeader(
    val iterations: Int,
    val salt: ByteArray,
    val noncePrefix: ByteArray,
    val chunkSize: Int
) {
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            write(MAGIC)
            writeByte(FORMAT_VERSION)
            writeByte(KDF_PBKDF2_SHA256)
            writeInt(iterations)
            writeByte(salt.size)
            write(salt)
            write(noncePrefix)
            writeInt(chunkSize)
            flush()
        }
        return bytes.toByteArray()
    }

    companion object {
        val MAGIC = "TAZBACKP".toByteArray(Charsets.US_ASCII)
        const val FORMAT_VERSION = 1
        const val KDF_PBKDF2_SHA256 = 1
        const val DEFAULT_ITERATIONS = 600_000
        const val MIN_ITERATIONS = 100_000
        const val MAX_ITERATIONS = 10_000_000
        const val SALT_BYTES = 16
        const val NONCE_PREFIX_BYTES = 8
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        private const val MIN_CHUNK_SIZE = 1024
        private const val MAX_CHUNK_SIZE = 1024 * 1024

        fun create(iterations: Int = DEFAULT_ITERATIONS, random: SecureRandom = SecureRandom()): BackupHeader =
            BackupHeader(
                iterations = iterations,
                salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) },
                noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also { random.nextBytes(it) },
                chunkSize = DEFAULT_CHUNK_SIZE
            )

        /**
         * Reads and checks a header. [minIterations] guards against a file that asks for a key
         * derivation weaker than TAZ ever writes; the upper bound stops a file from making the
         * device work for hours.
         */
        fun read(input: InputStream, minIterations: Int = MIN_ITERATIONS): BackupHeader {
            val data = DataInputStream(input)
            try {
                val magic = ByteArray(MAGIC.size)
                data.readFully(magic)
                if (!magic.contentEquals(MAGIC)) throw BackupException(BackupError.NOT_A_BACKUP)
                if (data.readUnsignedByte() != FORMAT_VERSION) throw BackupException(BackupError.UNSUPPORTED_VERSION)
                if (data.readUnsignedByte() != KDF_PBKDF2_SHA256) throw BackupException(BackupError.UNSUPPORTED_VERSION)
                val iterations = data.readInt()
                if (iterations < minIterations || iterations > MAX_ITERATIONS) {
                    throw BackupException(BackupError.UNSUPPORTED_VERSION)
                }
                val saltLength = data.readUnsignedByte()
                if (saltLength < SALT_BYTES || saltLength > 64) throw BackupException(BackupError.DAMAGED)
                val salt = ByteArray(saltLength).also { data.readFully(it) }
                val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also { data.readFully(it) }
                val chunkSize = data.readInt()
                if (chunkSize < MIN_CHUNK_SIZE || chunkSize > MAX_CHUNK_SIZE) throw BackupException(BackupError.DAMAGED)
                return BackupHeader(iterations, salt, noncePrefix, chunkSize)
            } catch (e: EOFException) {
                throw BackupException(BackupError.NOT_A_BACKUP)
            }
        }
    }
}

object BackupKeys {
    /**
     * Derives the AES-256 key for [header] from [passphrase]. The caller should wipe the
     * passphrase afterwards; the intermediate key bytes are wiped here.
     */
    fun derive(passphrase: CharArray, header: BackupHeader): SecretKey {
        val normalized = Normalizer.normalize(java.nio.CharBuffer.wrap(passphrase), Normalizer.Form.NFC).toCharArray()
        val spec = PBEKeySpec(normalized, header.salt, header.iterations, 256)
        try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return SecretKeySpec(encoded, "AES")
            } finally {
                encoded.fill(0)
            }
        } catch (e: Exception) {
            throw BackupException(BackupError.FAILED)
        } finally {
            spec.clearPassword()
            normalized.fill('\u0000')
        }
    }
}

private const val TAG_BYTES = 16
private const val TRANSFORMATION = "AES/GCM/NoPadding"

private fun chunkNonce(prefix: ByteArray, counter: Int): ByteArray =
    ByteArray(prefix.size + 4).also {
        prefix.copyInto(it)
        it[prefix.size] = (counter ushr 24).toByte()
        it[prefix.size + 1] = (counter ushr 16).toByte()
        it[prefix.size + 2] = (counter ushr 8).toByte()
        it[prefix.size + 3] = counter.toByte()
    }

private fun chunkAad(headerBytes: ByteArray, counter: Int, last: Boolean): ByteArray =
    ByteArray(headerBytes.size + 5).also {
        headerBytes.copyInto(it)
        val n = headerBytes.size
        it[n] = (counter ushr 24).toByte()
        it[n + 1] = (counter ushr 16).toByte()
        it[n + 2] = (counter ushr 8).toByte()
        it[n + 3] = counter.toByte()
        it[n + 4] = if (last) 1 else 0
    }

/**
 * Encrypts everything written to it into [out] in the format described above. Nothing is written
 * to [out] unencrypted except the header. [close] writes the last chunk and closes [out].
 */
class EncryptingOutputStream(
    private val out: OutputStream,
    private val key: SecretKey,
    private val header: BackupHeader
) : OutputStream() {

    private val headerBytes = header.encode()
    private val buffer = ByteArray(header.chunkSize)
    private var filled = 0
    private var counter = 0
    private var finished = false
    private val frame = DataOutputStream(out)

    init {
        out.write(headerBytes)
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!finished) { "Stream finished" }
        var offset = off
        var remaining = len
        while (remaining > 0) {
            // A full buffer is sealed only once more data arrives, so the last chunk is never empty
            // unless the whole stream is.
            if (filled == buffer.size) sealChunk(last = false)
            val count = minOf(remaining, buffer.size - filled)
            System.arraycopy(b, offset, buffer, filled, count)
            filled += count
            offset += count
            remaining -= count
        }
    }

    /** Writes the last chunk. Further writes are not allowed. Does not close [out]. */
    fun finish() {
        if (finished) return
        sealChunk(last = true)
        finished = true
        buffer.fill(0)
        frame.flush()
    }

    override fun flush() {
        frame.flush()
    }

    override fun close() {
        try {
            finish()
        } finally {
            out.close()
        }
    }

    private fun sealChunk(last: Boolean) {
        if (counter == Int.MAX_VALUE) throw BackupException(BackupError.TOO_LARGE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, chunkNonce(header.noncePrefix, counter)))
        cipher.updateAAD(chunkAad(headerBytes, counter, last))
        val sealed = cipher.doFinal(buffer, 0, filled)
        frame.writeByte(if (last) 1 else 0)
        frame.writeInt(sealed.size)
        frame.write(sealed)
        counter++
        filled = 0
    }
}

/**
 * Decrypts a stream written by [EncryptingOutputStream]. The header must already have been read
 * from [input] with [BackupHeader.read]. Every chunk is authenticated before any of its bytes are
 * returned; a failure throws [BackupException] ([BackupError.WRONG_PASSPHRASE] for the first
 * chunk, [BackupError.DAMAGED] later, [BackupError.INCOMPLETE] if the file ends early).
 */
class DecryptingInputStream(
    input: InputStream,
    private val key: SecretKey,
    private val header: BackupHeader
) : InputStream() {

    private val headerBytes = header.encode()
    private val data = DataInputStream(input)
    private var plain = ByteArray(0)
    private var position = 0
    private var counter = 0
    private var reachedEnd = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (position >= plain.size) {
            if (reachedEnd) return -1
            nextChunk()
        }
        val count = minOf(len, plain.size - position)
        System.arraycopy(plain, position, b, off, count)
        position += count
        return count
    }

    override fun close() {
        plain.fill(0)
        data.close()
    }

    private fun nextChunk() {
        val flag: Int
        val length: Int
        try {
            flag = data.readUnsignedByte()
            length = data.readInt()
        } catch (e: EOFException) {
            throw BackupException(BackupError.INCOMPLETE)
        }
        if (flag > 1 || length < TAG_BYTES || length > header.chunkSize + TAG_BYTES) {
            throw BackupException(if (counter == 0) BackupError.WRONG_PASSPHRASE else BackupError.DAMAGED)
        }
        val sealed = ByteArray(length)
        try {
            data.readFully(sealed)
        } catch (e: EOFException) {
            throw BackupException(BackupError.INCOMPLETE)
        }
        val last = flag == 1
        plain.fill(0)
        plain = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, chunkNonce(header.noncePrefix, counter)))
            cipher.updateAAD(chunkAad(headerBytes, counter, last))
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            // Wrong passphrase, tampering and corruption all end up here.
            throw BackupException(if (counter == 0) BackupError.WRONG_PASSPHRASE else BackupError.DAMAGED)
        }
        position = 0
        counter++
        if (last) {
            reachedEnd = true
            if (data.read() >= 0) throw BackupException(BackupError.DAMAGED) // data after the last chunk
        }
    }
}
