package com.zero7goat.taz.core.totp

import java.net.URLDecoder
import java.util.Base64

/** One two-factor account from an authenticator export. [toString] hides the key. */
class MigratedAccount(val issuer: String, val account: String, val otpauth: String) {
    override fun toString(): String = "MigratedAccount(issuer=$issuer, key=hidden)"
}

/** What one "Transfer accounts" QR code held. */
data class MigrationBatch(
    val accounts: List<MigratedAccount>,
    /** Counter-based (HOTP) accounts, which TAZ doesn't support. */
    val skipped: Int,
    val batchIndex: Int,
    val batchSize: Int,
    val batchId: Int
)

/**
 * Reads Google Authenticator's "Transfer accounts" QR codes (otpauth-migration://offline?data=…).
 * The data is a small protobuf message; this decodes just the fields needed, on the device.
 * Pure Kotlin/JVM, unit tested.
 */
object AuthenticatorMigration {

    fun isMigrationLink(text: String) = text.trim().startsWith("otpauth-migration://", ignoreCase = true)

    /** Null if [text] isn't a readable migration link. */
    fun parse(text: String): MigrationBatch? {
        if (!isMigrationLink(text)) return null
        val query = text.substringAfter('?', "")
        val raw = query.split('&').firstOrNull { it.startsWith("data=") }?.removePrefix("data=") ?: return null
        val bytes = try {
            val b64 = URLDecoder.decode(raw, "UTF-8").replace(' ', '+')
            Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            decodePayload(bytes)
        } catch (e: ProtoException) {
            null
        }
    }

    private fun decodePayload(bytes: ByteArray): MigrationBatch {
        val reader = ProtoReader(bytes)
        val accounts = mutableListOf<MigratedAccount>()
        var skipped = 0
        var batchSize = 1
        var batchIndex = 0
        var batchId = 0
        while (reader.hasMore()) {
            val (field, wire) = reader.tag()
            when {
                field == 1 && wire == 2 -> {
                    val parsed = decodeParameters(reader.bytes())
                    if (parsed == null) skipped++ else accounts += parsed
                }
                field == 3 && wire == 0 -> batchSize = reader.varint().toInt()
                field == 4 && wire == 0 -> batchIndex = reader.varint().toInt()
                field == 5 && wire == 0 -> batchId = reader.varint().toInt()
                else -> reader.skip(wire)
            }
        }
        return MigrationBatch(accounts, skipped, batchIndex, batchSize.coerceAtLeast(1), batchId)
    }

    private fun decodeParameters(bytes: ByteArray): MigratedAccount? {
        val reader = ProtoReader(bytes)
        var secret = ByteArray(0)
        var name = ""
        var issuer = ""
        var algorithm = 1
        var digits = 1
        var type = 2
        while (reader.hasMore()) {
            val (field, wire) = reader.tag()
            when {
                field == 1 && wire == 2 -> secret = reader.bytes()
                field == 2 && wire == 2 -> name = String(reader.bytes(), Charsets.UTF_8)
                field == 3 && wire == 2 -> issuer = String(reader.bytes(), Charsets.UTF_8)
                field == 4 && wire == 0 -> algorithm = reader.varint().toInt()
                field == 5 && wire == 0 -> digits = reader.varint().toInt()
                field == 6 && wire == 0 -> type = reader.varint().toInt()
                else -> reader.skip(wire)
            }
        }
        if (type == 1 || secret.isEmpty()) return null // HOTP, or nothing to use
        val algo = when (algorithm) {
            2 -> TotpAlgorithm.SHA256
            3 -> TotpAlgorithm.SHA512
            4 -> return null // MD5: not supported by TOTP apps in practice
            else -> TotpAlgorithm.SHA1
        }
        // The name is often "Issuer:account"; keep the account part.
        val account = if (issuer.isNotEmpty() && name.startsWith("$issuer:")) name.removePrefix("$issuer:").trim() else name.trim()
        val labelIssuer = issuer.ifEmpty { name.substringBefore(':', "").trim() }
        val spec = TotpSpec(secret, labelIssuer, account, algo, if (digits == 2) 8 else 6, 30)
        return MigratedAccount(labelIssuer, account, Totp.toUri(spec))
    }
}

class ProtoException : Exception("Malformed data")

/** Just enough of the protobuf wire format: varints and length-delimited fields. */
private class ProtoReader(private val data: ByteArray) {
    private var pos = 0

    fun hasMore() = pos < data.size

    fun tag(): Pair<Int, Int> {
        val key = varint()
        return (key ushr 3).toInt() to (key and 7).toInt()
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= data.size || shift > 63) throw ProtoException()
            val b = data[pos++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun bytes(): ByteArray {
        val length = varint()
        if (length < 0 || pos + length > data.size) throw ProtoException()
        val out = data.copyOfRange(pos, pos + length.toInt())
        pos += length.toInt()
        return out
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> advance(8)
            2 -> bytes()
            5 -> advance(4)
            else -> throw ProtoException()
        }
    }

    private fun advance(n: Int) {
        if (pos + n > data.size) throw ProtoException()
        pos += n
    }
}
