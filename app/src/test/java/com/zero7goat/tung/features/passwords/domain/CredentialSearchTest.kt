package com.zero7goat.tung.features.passwords.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialSearchTest {

    private fun matches(needle: String) = matchesCredentialSearch(
        websiteUrl = "https://github.com/login",
        domain = "github.com",
        needle = needle
    )

    @Test
    fun matchesTheWebsiteAddress() {
        assertTrue(matches("/login"))
        assertTrue(matches("HTTPS://GITHUB"))
    }

    @Test
    fun matchesTheDomain() {
        assertTrue(matches("github.com"))
        assertTrue(matches("hub"))
    }

    @Test
    fun doesNotMatchTheServiceNameOrPrivateFields() {
        // Only the address and domain are passed in, so a term that exists only in the service
        // name ("Work code host"), username, password or notes cannot match.
        assertFalse(matches("Work code host"))
        assertFalse(matches("alice@example.com"))
        assertFalse(matches("hunter2"))
        assertFalse(matches("recovery codes"))
    }
}
