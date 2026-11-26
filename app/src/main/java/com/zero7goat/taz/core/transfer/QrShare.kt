package com.zero7goat.taz.core.transfer

import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.CustomFields
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONException
import org.json.JSONObject

/** A login as it travels in a share code. [toString] hides secrets. */
class SharedLogin(
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val notes: String,
    val category: String,
    val fields: List<CustomField>
) {
    override fun toString(): String = "SharedLogin(name=$name, secrets=hidden)"
}

/**
 * Sends one login from one phone to another with a QR code, without any network. The login is
 * encrypted (AES-256-GCM) with a key derived (PBKDF2, 200,000 rounds) from a one-time code that
 * TAZ shows on the sending phone; the receiver scans the QR code and types that code. Someone who
 * only photographs the QR code can't read it. Pure Kotlin, unit tested.
 *
 *     QR text: "TAZSHARE1:" + Base64(salt 16 | nonce 12 | ciphertext)
 */
object QrShare {
    private const val PREFIX = "TAZSHARE1:"
    private const val ITERATIONS = 200_000
    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // no 0/O, 1/I/L
    const val CODE_LENGTH = 8

    /** QR codes hold about 2 KB comfortably; notes are shortened to stay under that. */
    private const val MAX_NOTES = 600

    fun isShareCode(text: String) = text.trim().startsWith(PREFIX)

    fun newCode(random: SecureRandom = SecureRandom()): String =
        (1..CODE_LENGTH).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")

    /** Shows "ABCD-EFGH" for easier reading aloud. */
    fun formatCode(code: String) = code.chunked(4).joinToString("-")

    fun encode(login: SharedLogin, code: String, random: SecureRandom = SecureRandom()): String {
        val json = JSONObject()
            .put("n", login.name)
            .put("u", login.url)
            .put("user", login.username)
            .put("p", login.password)
            .put("notes", login.notes.take(MAX_NOTES))
            .put("c", login.category)
            .put("f", CustomFields.encode(login.fields))
            .toString()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(PREFIX.toByteArray())
        val sealed = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(salt + nonce + sealed)
    }

    /** Null when [code] is wrong or the text isn't a TAZ share code. */
    fun decode(text: String, code: String): SharedLogin? {
        if (!isShareCode(text)) return null
        val raw = try {
            Base64.getDecoder().decode(text.trim().removePrefix(PREFIX))
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (raw.size < 16 + 12 + 16) return null
        val clean = code.uppercase().filter { it in CODE_ALPHABET }
        if (clean.length != CODE_LENGTH) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(clean, raw.copyOfRange(0, 16)), GCMParameterSpec(128, raw.copyOfRange(16, 28)))
            cipher.updateAAD(PREFIX.toByteArray())
            val o = JSONObject(String(cipher.doFinal(raw.copyOfRange(28, raw.size)), Charsets.UTF_8))
            SharedLogin(
                name = o.optString("n"),
                url = o.optString("u"),
                username = o.optString("user"),
                password = o.optString("p"),
                notes = o.optString("notes"),
                category = o.optString("c", "login"),
                fields = CustomFields.decode(o.optString("f", "[]"))
            )
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: JSONException) {
            null
        }
    }

    private fun key(code: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
