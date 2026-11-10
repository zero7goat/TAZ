package com.zero7goat.taz.features.passwords.domain

/**
 * Whether a saved login matches a search.
 *
 * Only the website address and its domain can be searched. The function takes no service
 * name, username, password or note on purpose, so they cannot be matched by accident.
 * [needle] must already be trimmed and non-empty.
 */
fun matchesCredentialSearch(
    websiteUrl: String,
    domain: String,
    needle: String
): Boolean =
    websiteUrl.contains(needle, ignoreCase = true) ||
        domain.contains(needle, ignoreCase = true)
