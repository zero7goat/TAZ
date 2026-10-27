package com.zero7goat.tung.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Passcode hashing with PBKDF2-HMAC-SHA256 (a standard, vetted password-hashing function; no
 * custom cryptography). Pure JVM code so it can be unit tested.
 *
 * The salt is random per passcode, the iteration count is stored with the result so it can be
 * raised later, and comparison is constant-time.
 */
object PasscodeHasher {

    const val DEFAULT_ITERATIONS = 200_000
    const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    fun hash(passcode: CharArray, salt: ByteArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(passcode, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun matches(
        passcode: CharArray,
        salt: ByteArray,
        iterations: Int,
        expected: ByteArray
    ): Boolean {
        val actual = hash(passcode, salt, iterations)
        try {
            return MessageDigest.isEqual(actual, expected)
        } finally {
            actual.fill(0)
        }
    }
}
