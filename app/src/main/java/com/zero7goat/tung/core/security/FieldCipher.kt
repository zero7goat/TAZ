package com.zero7goat.tung.core.security

/**
 * Encrypts and decrypts short text fields with authenticated encryption.
 *
 * [associatedData] is authenticated but not stored: decrypting with different associated
 * data fails. Callers bind a value to where it belongs (for example row id plus field name)
 * so that ciphertext cannot be moved between rows or fields.
 */
interface FieldCipher {
    @Throws(FieldCipherException::class)
    fun encrypt(plaintext: String, associatedData: String): ByteArray

    @Throws(FieldCipherException::class)
    fun decrypt(blob: ByteArray, associatedData: String): String
}

/**
 * Thrown when a value cannot be encrypted or decrypted. Messages are deliberately generic
 * and never contain the data involved.
 */
class FieldCipherException(message: String) : Exception(message)
