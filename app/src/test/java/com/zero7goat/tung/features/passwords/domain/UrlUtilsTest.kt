package com.zero7goat.tung.features.passwords.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun keepsHttpAndHttpsAddresses() {
        assertEquals("https://example.com/a", openableUrl("https://example.com/a"))
        assertEquals("http://example.com", openableUrl("http://example.com"))
        assertEquals("http://localhost:8080", openableUrl("http://localhost:8080"))
    }

    @Test
    fun treatsABareDomainAsHttps() {
        assertEquals("https://github.com", openableUrl("github.com"))
        assertEquals("https://github.com", openableUrl("  github.com  "))
    }

    @Test
    fun rejectsEverythingThatIsNotAWebAddress() {
        assertNull(openableUrl(""))
        assertNull(openableUrl("   "))
        assertNull(openableUrl("https://"))
        assertNull(openableUrl("exa mple.com"))
        assertNull(openableUrl("javascript:alert(1)"))
        assertNull(openableUrl("javascript://example.com/%0aalert(1)"))
        assertNull(openableUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(openableUrl("file:///etc/passwd"))
        assertNull(openableUrl("ftp://example.com"))
        assertNull(openableUrl("data:text/html,hi"))
    }

    @Test
    fun extractsALowercaseDomainWithoutWww() {
        assertEquals("github.com", extractDomain("https://WWW.GitHub.com/login"))
        assertEquals("example.org", extractDomain("example.org"))
        assertEquals("sub.example.com", extractDomain("http://sub.example.com:8080/x?y=1"))
    }

    @Test
    fun domainIsEmptyWhenThereIsNoValidAddress() {
        assertEquals("", extractDomain(""))
        assertEquals("", extractDomain("javascript:alert(1)"))
        assertEquals("", extractDomain("not a url"))
    }
}
