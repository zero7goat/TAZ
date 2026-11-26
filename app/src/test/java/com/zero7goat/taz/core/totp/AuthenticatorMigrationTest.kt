package com.zero7goat.taz.core.totp

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Builds Google Authenticator export payloads by hand (synthetic accounts only). */
class AuthenticatorMigrationTest {

    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7fL.inv() == 0L) {
                write(v.toInt()); return
            }
            write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun ByteArrayOutputStream.bytesField(field: Int, data: ByteArray) {
        varint(((field shl 3) or 2).toLong()); varint(data.size.toLong()); write(data)
    }

    private fun ByteArrayOutputStream.intField(field: Int, value: Long) {
        varint((field shl 3).toLong()); varint(value)
    }

    private fun account(secret: ByteArray, name: String, issuer: String, algorithm: Int = 1, digits: Int = 1, type: Int = 2) =
        ByteArrayOutputStream().apply {
            bytesField(1, secret)
            bytesField(2, name.toByteArray())
            bytesField(3, issuer.toByteArray())
            intField(4, algorithm.toLong())
            intField(5, digits.toLong())
            intField(6, type.toLong())
        }.toByteArray()

    private fun link(vararg accounts: ByteArray, size: Int = 1, index: Int = 0): String {
        val payload = ByteArrayOutputStream().apply {
            accounts.forEach { bytesField(1, it) }
            intField(2, 1)
            intField(3, size.toLong())
            intField(4, index.toLong())
            intField(5, 777)
        }.toByteArray()
        return "otpauth-migration://offline?data=" + URLEncoder.encode(Base64.getEncoder().encodeToString(payload), "UTF-8")
    }

    private val secret = "12345678901234567890".toByteArray()

    @Test
    fun readsAccountsAndTurnsThemIntoWorkingCodes() {
        val batch = AuthenticatorMigration.parse(
            link(
                account(secret, "Example:alice@example.test", "Example"),
                account(secret, "bob", "Shop", algorithm = 2, digits = 2)
            )
        )
        assertNotNull(batch)
        assertEquals(2, batch!!.accounts.size)
        val first = batch.accounts[0]
        assertEquals("Example", first.issuer)
        assertEquals("alice@example.test", first.account)
        val spec = Totp.parse(first.otpauth)!!
        // Same secret as RFC 6238's test vector, so the code is known.
        assertEquals("287082", Totp.code(spec, 59_000L))
        val second = Totp.parse(batch.accounts[1].otpauth)!!
        assertEquals(TotpAlgorithm.SHA256, second.algorithm)
        assertEquals(8, second.digits)
    }

    @Test
    fun skipsCounterBasedAccountsAndReportsBatches() {
        val batch = AuthenticatorMigration.parse(link(account(secret, "x", "Hotp", type = 1), size = 3, index = 1))!!
        assertEquals(0, batch.accounts.size)
        assertEquals(1, batch.skipped)
        assertEquals(3, batch.batchSize)
        assertEquals(1, batch.batchIndex)
    }

    @Test
    fun rejectsWhatIsNotAnExport() {
        assertNull(AuthenticatorMigration.parse("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQ"))
        assertNull(AuthenticatorMigration.parse("otpauth-migration://offline?data=%%%"))
        // Truncated payload: the account record claims more bytes than are left.
        val good = link(account(secret, "a", "b"))
        val data = good.substringAfter("data=")
        val payload = Base64.getDecoder().decode(java.net.URLDecoder.decode(data, "UTF-8"))
        val cut = Base64.getEncoder().encodeToString(payload.copyOf(payload.size / 2))
        assertNull(AuthenticatorMigration.parse("otpauth-migration://offline?data=" + URLEncoder.encode(cut, "UTF-8")))
    }
}
