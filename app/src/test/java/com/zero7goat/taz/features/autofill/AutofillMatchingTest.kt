package com.zero7goat.taz.features.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillMatchingTest {

    private val logins = listOf(
        MatchCandidate("1", "Google", "accounts.google.com"),
        MatchCandidate("2", "Gmail", "google.com"),
        MatchCandidate("3", "Instagram", "instagram.com"),
        MatchCandidate("4", "BBC", "bbc.co.uk"),
        MatchCandidate("5", "Evil", "google.com.evil.test"),
        MatchCandidate("6", "My Bank App", ""),
        MatchCandidate("7", "No site", "")
    )

    @Test
    fun websitesMatchTheSameSiteOnlyWithTheExactHostFirst() {
        val result = AutofillMatcher.match(logins, "accounts.google.com", "com.android.chrome").map { it.id }
        assertEquals(listOf("1", "2"), result)
        assertTrue(AutofillMatcher.match(logins, "www.bbc.co.uk", "x").map { it.id } == listOf("4"))
        // A look-alike host never matches the real one.
        assertFalse(AutofillMatcher.match(logins, "google.com", "x").any { it.id == "5" })
    }

    @Test
    fun appsMatchByTheWordsOfTheirPackageName() {
        assertEquals(listOf("3"), AutofillMatcher.match(logins, null, "com.instagram.android").map { it.id })
        assertEquals(listOf("6"), AutofillMatcher.match(logins, null, "com.example.mybankapp").map { it.id }.filter { it == "6" })
        assertTrue(AutofillMatcher.match(logins, null, "com.android.app").isEmpty())
    }

    @Test
    fun baseDomainHandlesCountrySecondLevels() {
        assertEquals("google.com", AutofillMatcher.baseDomain("mail.google.com"))
        assertEquals("bbc.co.uk", AutofillMatcher.baseDomain("news.bbc.co.uk"))
        assertEquals("example.org", AutofillMatcher.baseDomain("www.example.org"))
        assertFalse(AutofillMatcher.sameSite("", "example.org"))
    }

    @Test
    fun namesNewLoginsFromTheAppPackage() {
        assertEquals("Instagram", AutofillMatcher.nameForPackage("com.instagram.android"))
        assertEquals("Youtube", AutofillMatcher.nameForPackage("com.google.android.youtube"))
    }

    @Test
    fun classifiesFieldsFromHintsHtmlAndInputTypes() {
        assertEquals(FieldKind.PASSWORD, FieldClassifier.classify(FieldTraits(autofillHints = listOf("password"))))
        assertEquals(FieldKind.USERNAME, FieldClassifier.classify(FieldTraits(autofillHints = listOf("emailAddress"))))
        assertEquals(FieldKind.PASSWORD, FieldClassifier.classify(FieldTraits(htmlTag = "input", htmlType = "password")))
        assertEquals(FieldKind.USERNAME, FieldClassifier.classify(FieldTraits(htmlTag = "input", htmlType = "email")))
        assertEquals(FieldKind.OTP, FieldClassifier.classify(FieldTraits(htmlTag = "input", htmlAutocomplete = "one-time-code")))
        assertEquals(FieldKind.OTHER, FieldClassifier.classify(FieldTraits(htmlTag = "input", htmlType = "search", htmlName = "q")))
        // InputType.TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
        assertEquals(FieldKind.PASSWORD, FieldClassifier.classify(FieldTraits(inputType = 0x81)))
        // InputType.TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertEquals(FieldKind.USERNAME, FieldClassifier.classify(FieldTraits(inputType = 0x21)))
        assertEquals(FieldKind.USERNAME, FieldClassifier.classify(FieldTraits(inputType = 0x01, idEntry = "login_user")))
        assertEquals(FieldKind.OTHER, FieldClassifier.classify(FieldTraits(inputType = 0x01, idEntry = "search_box")))
        assertEquals(FieldKind.OTHER, FieldClassifier.classify(FieldTraits(inputType = 0x01, hint = "Shipping address")))
    }
}
