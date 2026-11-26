package com.zero7goat.taz.core.totp

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords (RFC 6238), the 6-digit codes of authenticator apps.
 * Everything is computed on the device from the stored secret; nothing goes online.
 * Pure Kotlin/JVM, so it is unit tested.
 */
enum class TotpAlgorithm(val key: String, val macName: String) {
    SHA1("SHA1", "HmacSHA1"),
    SHA256("SHA256", "HmacSHA256"),
    SHA512("SHA512", "HmacSHA512");

    companion object {
        fun fromKey(key: String?): TotpAlgorithm? =
            if (key.isNullOrBlank()) SHA1 else entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}

/** A parsed two-factor setup. [toString] hides the secret. */
class TotpSpec(
    val secret: ByteArray,
    val issuer: String = "",
    val account: String = "",
    val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    val digits: Int = 6,
    val periodSeconds: Int = 30
) {
    override fun toString(): String = "TotpSpec(issuer=$issuer, secret=hidden)"
}

object Totp {

    /**
     * Reads what a user pastes or a QR code holds: an `otpauth://totp/...` link, or a bare
     * Base32 setup key (spaces, dashes and lower case are fine). Returns null if it is neither.
     * HOTP (counter-based) links are not supported and return null.
     */
    fun parse(text: String): TotpSpec? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("otpauth://", ignoreCase = true)) parseUri(trimmed) else {
            val secret = Base32.decode(trimmed) ?: return null
            if (secret.size < MIN_SECRET_BYTES) null else TotpSpec(secret)
        }
    }

    fun isValid(text: String): Boolean = parse(text) != null

    /** The code for [spec] at [timeMillis]. */
    fun code(spec: TotpSpec, timeMillis: Long): String {
        val counter = Math.floorDiv(timeMillis / 1000, spec.periodSeconds.toLong())
        val mac = Mac.getInstance(spec.algorithm.macName)
        mac.init(SecretKeySpec(spec.secret, spec.algorithm.macName))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val modulo = POWERS[spec.digits]
        return (binary % modulo).toString().padStart(spec.digits, '0')
    }

    /** Seconds until the current code changes (1..period). */
    fun secondsLeft(spec: TotpSpec, timeMillis: Long): Int {
        val period = spec.periodSeconds
        val elapsed = Math.floorMod(timeMillis / 1000, period.toLong()).toInt()
        return period - elapsed
    }

    /** "123 456" for 6 digits, "1234 5678" for 8: easier to read off the screen. */
    fun group(code: String): String =
        if (code.length < 6) code else code.substring(0, code.length / 2) + " " + code.substring(code.length / 2)

    /** A standard otpauth link for [spec], used by the CSV export so other apps can import it. */
    fun toUri(spec: TotpSpec): String {
        val label = listOf(spec.issuer, spec.account).filter { it.isNotBlank() }.joinToString(":").ifEmpty { "TAZ" }
        val params = buildList {
            add("secret=" + Base32.encode(spec.secret))
            if (spec.issuer.isNotBlank()) add("issuer=" + encode(spec.issuer))
            if (spec.algorithm != TotpAlgorithm.SHA1) add("algorithm=" + spec.algorithm.key)
            if (spec.digits != 6) add("digits=" + spec.digits)
            if (spec.periodSeconds != 30) add("period=" + spec.periodSeconds)
        }
        return "otpauth://totp/" + encode(label) + "?" + params.joinToString("&")
    }

    private fun parseUri(uri: String): TotpSpec? {
        val afterScheme = uri.substring("otpauth://".length)
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return null
        if (!afterScheme.substring(0, slash).equals("totp", ignoreCase = true)) return null
        val rest = afterScheme.substring(slash + 1)
        val question = rest.indexOf('?')
        val label = decode(if (question < 0) rest else rest.substring(0, question))
        val query = if (question < 0) "" else rest.substring(question + 1)
        val params = query.split('&').filter { it.isNotEmpty() }.associate { part ->
            val eq = part.indexOf('=')
            if (eq < 0) part.lowercase() to "" else part.substring(0, eq).lowercase() to decode(part.substring(eq + 1))
        }
        val secret = Base32.decode(params["secret"].orEmpty()) ?: return null
        if (secret.size < MIN_SECRET_BYTES) return null
        val algorithm = TotpAlgorithm.fromKey(params["algorithm"]) ?: return null
        val digits = params["digits"]?.toIntOrNull() ?: 6
        val period = params["period"]?.toIntOrNull() ?: 30
        if (digits !in 6..8 || period !in 1..300) return null
        val colon = label.indexOf(':')
        val labelIssuer = if (colon >= 0) label.substring(0, colon).trim() else ""
        val account = if (colon >= 0) label.substring(colon + 1).trim() else label.trim()
        return TotpSpec(
            secret = secret,
            issuer = params["issuer"]?.trim().orEmpty().ifEmpty { labelIssuer },
            account = account,
            algorithm = algorithm,
            digits = digits,
            periodSeconds = period
        )
    }

    private fun decode(text: String): String =
        try {
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            text
        }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    // 16 Base32 characters (80 bits): the shortest keys real services hand out. Shorter text is
    // almost certainly a typo or an ordinary word, not a setup key.
    private const val MIN_SECRET_BYTES = 10
    private val POWERS = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)
}

/** RFC 4648 Base32, as used by authenticator setup keys. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Decodes a setup key, ignoring spaces, dashes, padding and case. Null if it has other characters. */
    fun decode(text: String): ByteArray? {
        val clean = text.uppercase().filterNot { it == ' ' || it == '-' || it == '=' || it == '\t' }
        if (clean.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    fun encode(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(ALPHABET[(buffer shr bits) and 0x1f])
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
    }
}
