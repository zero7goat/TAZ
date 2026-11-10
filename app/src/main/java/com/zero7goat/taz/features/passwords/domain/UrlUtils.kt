package com.zero7goat.taz.features.passwords.domain

import java.net.URI

/**
 * Returns [raw] as a web address that is safe to open, or null.
 *
 * Only http and https are accepted. An address without a scheme (like "github.com") is
 * treated as https. Anything else (javascript:, intent:, file:, ftp:, spaces, no host)
 * returns null so it is never opened.
 */
fun openableUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = try {
        URI(withScheme)
    } catch (e: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host
    if (host.isNullOrBlank()) return null
    return withScheme
}

/** The lowercase host of [raw] without a leading "www.", or an empty string if there is none. */
fun extractDomain(raw: String): String {
    val url = openableUrl(raw) ?: return ""
    val host = try {
        URI(url).host
    } catch (e: Exception) {
        null
    } ?: return ""
    return host.lowercase().removePrefix("www.")
}
