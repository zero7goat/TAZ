package com.zero7goat.taz.core.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codes are checked against the test vectors published in RFC 6238, appendix B. */
class TotpTest {

    private val sha1Secret = "12345678901234567890".toByteArray()
    private val sha256Secret = "12345678901234567890123456789012".toByteArray()
    private val sha512Secret = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()

    private fun spec(secret: ByteArray, algorithm: TotpAlgorithm) = TotpSpec(secret, algorithm = algorithm, digits = 8)

    @Test
    fun matchesRfc6238Sha1Vectors() {
        val s = spec(sha1Secret, TotpAlgorithm.SHA1)
        assertEquals("94287082", Totp.code(s, 59_000L))
        assertEquals("07081804", Totp.code(s, 1_111_111_109_000L))
        assertEquals("14050471", Totp.code(s, 1_111_111_111_000L))
        assertEquals("89005924", Totp.code(s, 1_234_567_890_000L))
        assertEquals("69279037", Totp.code(s, 2_000_000_000_000L))
        assertEquals("65353130", Totp.code(s, 20_000_000_000_000L))
    }

    @Test
    fun matchesRfc6238Sha256AndSha512Vectors() {
        assertEquals("46119246", Totp.code(spec(sha256Secret, TotpAlgorithm.SHA256), 59_000L))
        assertEquals("91819424", Totp.code(spec(sha256Secret, TotpAlgorithm.SHA256), 1_234_567_890_000L))
        assertEquals("90693936", Totp.code(spec(sha512Secret, TotpAlgorithm.SHA512), 59_000L))
        assertEquals("93441116", Totp.code(spec(sha512Secret, TotpAlgorithm.SHA512), 1_234_567_890_000L))
    }

    @Test
    fun sixDigitCodesAreTheLastSixDigits() {
        val s = TotpSpec(sha1Secret)
        assertEquals("287082", Totp.code(s, 59_000L))
    }

    @Test
    fun base32RoundTripsAndIgnoresSpacingAndCase() {
        val key = Base32.encode(sha1Secret)
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", key)
        assertEquals(sha1Secret.toList(), Base32.decode("gezd gnbv gy3t-qojq gezd gnbv gy3t qojq")!!.toList())
        assertNull(Base32.decode("not base32!"))
    }

    @Test
    fun parsesBareKeysAndOtpauthLinks() {
        val bare = Totp.parse("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertNotNull(bare)
        assertEquals(6, bare!!.digits)
        assertEquals(30, bare.periodSeconds)

        val link = Totp.parse("otpauth://totp/Example%20Co:alice@example.com?secret=GEZDGNBVGY3TQOJQ&issuer=Example%20Co&digits=8&period=60&algorithm=SHA256")
        assertNotNull(link)
        assertEquals("Example Co", link!!.issuer)
        assertEquals("alice@example.com", link.account)
        assertEquals(8, link.digits)
        assertEquals(60, link.periodSeconds)
        assertEquals(TotpAlgorithm.SHA256, link.algorithm)

        val issuerFromLabel = Totp.parse("otpauth://totp/Shop:bob?secret=GEZDGNBVGY3TQOJQ")
        assertEquals("Shop", issuerFromLabel!!.issuer)
    }

    @Test
    fun rejectsWhatIsNotATotpSetup() {
        assertFalse(Totp.isValid(""))
        assertFalse(Totp.isValid("hello world"))
        assertFalse(Totp.isValid("AB")) // far too short to be a real key
        assertFalse(Totp.isValid("otpauth://hotp/x?secret=GEZDGNBVGY3TQOJQ&counter=1"))
        assertFalse(Totp.isValid("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQ&digits=12"))
        assertFalse(Totp.isValid("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQ&algorithm=MD5"))
        assertFalse(Totp.isValid("otpauth://totp/x"))
        assertTrue(Totp.isValid("otpauth://TOTP/x?SECRET=GEZDGNBVGY3TQOJQ"))
    }

    @Test
    fun exportedLinkParsesBackToTheSameCodes() {
        val original = Totp.parse("otpauth://totp/A%20B:me?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=A%20B&digits=7")!!
        val again = Totp.parse(Totp.toUri(original))!!
        assertEquals("A B", again.issuer)
        assertEquals(Totp.code(original, 1_700_000_000_000L), Totp.code(again, 1_700_000_000_000L))
    }

    @Test
    fun secondsLeftCountsDownWithinThePeriod() {
        val s = TotpSpec(sha1Secret)
        assertEquals(30, Totp.secondsLeft(s, 60_000L))
        assertEquals(1, Totp.secondsLeft(s, 89_000L))
        assertEquals("123 456", Totp.group("123456"))
        assertEquals("1234 5678", Totp.group("12345678"))
    }

    @Test
    fun toStringNeverShowsTheSecret() {
        assertFalse(TotpSpec(sha1Secret, issuer = "X").toString().contains("GEZ"))
    }
}
