package com.zero7goat.taz.core.storage

import com.zero7goat.taz.core.security.FieldCipher
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultFileException(message: String) : IOException(message)

/**
 * Encrypts the stored copies of files (TAZ 2.0). Pure JVM so it is unit tested.
 *
 * Each file gets its own random AES-256 key. That key is encrypted ("wrapped") with the Keystore
 * key through [keyWrapper] and kept in the file's header, so the Keystore is used once per file,
 * not for every block. The contents are AES-256-GCM in 64 KiB chunks; each chunk's nonce and
 * associated data bind it to the file id, its position and whether it is the last one, so chunks
 * cannot be swapped, reordered, dropped or cut off without the file failing to open.
 *
 *     "TAZF" | version 1 | wrapped key length (int) | wrapped key | nonce prefix (8 bytes)
 *     then chunks: ciphertext length (int) | ciphertext (plaintext + 16-byte tag)
 *
 * Files from before 2.0 are plain; [isEncrypted] tells them apart by the header, so a file that
 * was being converted when the app stopped is always read correctly.
 */
class VaultFileCipher(private val keyWrapper: FieldCipher, private val random: SecureRandom = SecureRandom()) {

    fun isEncrypted(file: File): Boolean =
        try {
            FileInputStream(file).use { input ->
                val head = ByteArray(5)
                var read = 0
                while (read < 5) {
                    val n = input.read(head, read, 5 - read)
                    if (n < 0) return false
                    read += n
                }
                head.copyOfRange(0, 4).contentEquals(MAGIC) && head[4].toInt() == VERSION
            }
        } catch (e: IOException) {
            false
        }

    /** Encrypts everything from [input] into [output] for the file [fileId]. Returns the plain size. */
    fun encrypt(input: InputStream, output: OutputStream, fileId: String): Long {
        val key = ByteArray(32).also { random.nextBytes(it) }
        val prefix = ByteArray(8).also { random.nextBytes(it) }
        try {
            val wrapped = keyWrapper.encrypt(Base64.getEncoder().encodeToString(key), keyAad(fileId))
            val out = DataOutputStream(output)
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.writeInt(wrapped.size)
            out.write(wrapped)
            out.write(prefix)

            val keySpec = SecretKeySpec(key, "AES")
            var current = ByteArray(CHUNK)
            var currentLength = readFully(input, current)
            var next = ByteArray(CHUNK)
            var index = 0
            var total = 0L
            while (true) {
                // Look one chunk ahead so the last chunk can be marked as last.
                val nextLength = if (currentLength == CHUNK) readFully(input, next) else 0
                val last = nextLength == 0
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(prefix, index)))
                cipher.updateAAD(chunkAad(fileId, index, last))
                val sealed = cipher.doFinal(current, 0, currentLength)
                out.writeInt(sealed.size)
                out.write(sealed)
                total += currentLength
                if (last) break
                val swap = current
                current = next
                next = swap
                currentLength = nextLength
                index++
            }
            out.flush()
            current.fill(0)
            next.fill(0)
            return total
        } catch (e: GeneralSecurityException) {
            throw VaultFileException("Encryption failed")
        } finally {
            key.fill(0)
        }
    }

    /** Opens [file] for reading: decrypting when it is encrypted, as it is otherwise. */
    fun open(file: File, fileId: String): InputStream =
        if (isEncrypted(file)) DecryptingStream(FileInputStream(file), fileId) else FileInputStream(file)

    /** The plain size of [file] without decrypting it (just walking the chunk headers). */
    fun plainLength(file: File): Long {
        if (!isEncrypted(file)) return file.length()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(5)
            val wrappedLength = raf.readInt()
            raf.seek(5L + 4 + wrappedLength + 8)
            var total = 0L
            while (raf.filePointer < raf.length()) {
                val sealed = raf.readInt()
                if (sealed < TAG_BYTES) throw VaultFileException("Damaged file")
                total += sealed - TAG_BYTES
                raf.seek(raf.filePointer + sealed)
            }
            return total
        }
    }

    private inner class DecryptingStream(source: InputStream, private val fileId: String) : InputStream() {
        // Buffered, so the reader can peek past a chunk to see whether it is the last.
        private val input = DataInputStream(BufferedInputStream(source, CHUNK + 64))
        private val keySpec: SecretKeySpec
        private val prefix = ByteArray(8)
        private var buffer = ByteArray(0)
        private var position = 0
        private var index = 0
        private var finished = false

        init {
            try {
                input.skipBytes(5)
                val wrappedLength = input.readInt()
                if (wrappedLength !in 1..4096) throw VaultFileException("Damaged file")
                val wrapped = ByteArray(wrappedLength).also { input.readFully(it) }
                input.readFully(prefix)
                val raw = Base64.getDecoder().decode(keyWrapper.decrypt(wrapped, keyAad(fileId)))
                keySpec = SecretKeySpec(raw, "AES")
                raw.fill(0)
            } catch (e: IOException) {
                input.close()
                throw e
            } catch (e: Exception) {
                input.close()
                throw VaultFileException("This file can't be decrypted")
            }
        }

        private fun fill(): Boolean {
            if (finished) return false
            val sealedLength = try {
                input.readInt()
            } catch (e: EOFException) {
                throw VaultFileException("File is incomplete")
            }
            if (sealedLength !in TAG_BYTES..(CHUNK + TAG_BYTES)) throw VaultFileException("Damaged file")
            val sealed = ByteArray(sealedLength).also { input.readFully(it) }
            // The last chunk is the one followed by nothing; its associated data must say so.
            input.mark(1)
            val last = input.read() < 0
            if (!last) input.reset()
            buffer = try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(prefix, index)))
                cipher.updateAAD(chunkAad(fileId, index, last))
                cipher.doFinal(sealed)
            } catch (e: GeneralSecurityException) {
                throw VaultFileException("File was changed or damaged")
            }
            position = 0
            index++
            if (last) finished = true
            return true
        }

        override fun read(): Int {
            while (position >= buffer.size) if (!fill()) return -1
            return buffer[position++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position >= buffer.size) if (!fill()) return -1
            val n = minOf(len, buffer.size - position)
            System.arraycopy(buffer, position, b, off, n)
            position += n
            return n
        }

        override fun close() {
            buffer.fill(0)
            input.close()
        }
    }

    private fun readFully(input: InputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val n = input.read(into, total, into.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun nonce(prefix: ByteArray, index: Int) = ByteArray(12).also {
        System.arraycopy(prefix, 0, it, 0, 8)
        it[8] = (index ushr 24).toByte(); it[9] = (index ushr 16).toByte(); it[10] = (index ushr 8).toByte(); it[11] = index.toByte()
    }

    companion object {
        private val MAGIC = byteArrayOf('T'.code.toByte(), 'A'.code.toByte(), 'Z'.code.toByte(), 'F'.code.toByte())
        private const val VERSION = 1
        const val CHUNK = 64 * 1024
        private const val TAG_BYTES = 16
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        // DO NOT CHANGE: stored files depend on these exact values.
        fun keyAad(fileId: String) = "taz.file.v1|$fileId|key"
        fun chunkAad(fileId: String, index: Int, last: Boolean) = "taz.file.v1|$fileId|$index|${if (last) 1 else 0}".toByteArray()
    }
}
