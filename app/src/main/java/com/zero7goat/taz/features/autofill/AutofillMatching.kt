package com.zero7goat.taz.features.autofill

/*
 * The decisions behind autofill, as pure Kotlin so they are unit tested: which input fields are
 * usernames or passwords, and which saved logins fit the website or app asking.
 */

enum class FieldKind { USERNAME, PASSWORD, OTP, OTHER }

/** What a form field tells about itself, gathered from Android's view structure. */
data class FieldTraits(
    val autofillHints: List<String> = emptyList(),
    /** android.text.InputType bits. */
    val inputType: Int = 0,
    val htmlTag: String = "",
    val htmlType: String = "",
    val htmlAutocomplete: String = "",
    val htmlName: String = "",
    /** The view id name, like "login_email". */
    val idEntry: String = "",
    /** The placeholder / hint text shown in the field. */
    val hint: String = ""
)

object FieldClassifier {

    // android.text.InputType values, copied so this stays pure Kotlin.
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    private const val TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    private const val TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val NUMBER_VARIATION_PASSWORD = 0x00000010

    private val passwordWords = listOf("password", "passwd", "passcode", "pwd")
    private val usernameWords = listOf("user", "email", "e-mail", "login", "account", "identifier", "phone", "mobile")
    private val otpWords = listOf("otp", "one-time", "onetime", "2fa", "totp", "verification", "authcode", "auth_code")
    private val ignoreWords = listOf("search", "captcha", "promo", "coupon", "zip", "postal", "address", "city", "comment", "message")

    fun classify(t: FieldTraits): FieldKind {
        val hints = t.autofillHints.map { it.lowercase() }
        // 1. Explicit hints from the app or page win.
        if (hints.any { it == "password" || it == "current-password" || it == "new-password" || it == "passwordauto" }) return FieldKind.PASSWORD
        if (hints.any { it.contains("otp") || it == "one-time-code" }) return FieldKind.OTP
        if (hints.any { it == "username" || it == "emailaddress" || it == "email" || it == "phone" || it == "phonenumber" }) return FieldKind.USERNAME
        val autocomplete = t.htmlAutocomplete.lowercase()
        if (autocomplete.contains("password")) return FieldKind.PASSWORD
        if (autocomplete.contains("one-time-code")) return FieldKind.OTP
        if (autocomplete.contains("username") || autocomplete.contains("email")) return FieldKind.USERNAME

        // 2. HTML input types.
        val htmlType = t.htmlType.lowercase()
        if (htmlType == "password") return FieldKind.PASSWORD
        if (htmlType == "email") return FieldKind.USERNAME
        if (t.htmlTag.isNotEmpty() && t.htmlTag.lowercase() != "input") return FieldKind.OTHER
        if (htmlType in setOf("hidden", "submit", "button", "checkbox", "radio", "search", "file", "image", "reset")) return FieldKind.OTHER

        // 3. Android input types.
        val cls = t.inputType and TYPE_MASK_CLASS
        val variation = t.inputType and TYPE_MASK_VARIATION
        if (cls == TYPE_CLASS_TEXT && variation in setOf(TEXT_VARIATION_PASSWORD, TEXT_VARIATION_VISIBLE_PASSWORD, TEXT_VARIATION_WEB_PASSWORD)) {
            return FieldKind.PASSWORD
        }
        if (cls == TYPE_CLASS_NUMBER && variation == NUMBER_VARIATION_PASSWORD) return FieldKind.PASSWORD
        if (cls == TYPE_CLASS_TEXT && (variation == TEXT_VARIATION_EMAIL_ADDRESS || variation == TEXT_VARIATION_WEB_EMAIL_ADDRESS)) {
            return FieldKind.USERNAME
        }

        // 4. Words in the field's id, name or placeholder.
        val words = listOf(t.idEntry, t.htmlName, t.hint).joinToString(" ").lowercase()
        if (words.isBlank() || ignoreWords.any { words.contains(it) }) return FieldKind.OTHER
        if (otpWords.any { words.contains(it) }) return FieldKind.OTP
        if (passwordWords.any { words.contains(it) }) return FieldKind.PASSWORD
        if (usernameWords.any { words.contains(it) }) return FieldKind.USERNAME
        return FieldKind.OTHER
    }

    /** True if a text field could be a username box sitting next to a password box. */
    fun isPlainTextInput(t: FieldTraits): Boolean {
        val htmlType = t.htmlType.lowercase()
        if (t.htmlTag.isNotEmpty()) return t.htmlTag.equals("input", ignoreCase = true) && htmlType in setOf("", "text", "email", "tel")
        val cls = t.inputType and TYPE_MASK_CLASS
        return cls == TYPE_CLASS_TEXT || cls == 3 /* phone */
    }
}

/** A saved login as matching sees it: only plain, non-secret columns. */
data class MatchCandidate(val id: String, val serviceName: String, val domain: String)

object AutofillMatcher {

    /** Package-name parts that say nothing about which service an app belongs to. */
    private val genericParts = setOf(
        "com", "org", "net", "io", "co", "app", "apps", "android", "mobile", "client", "www", "main", "prod",
        "release", "lite", "free", "pro", "beta", "official", "the", "google", "gms", "de", "uk", "in", "us"
    )

    private val secondLevelSuffixes = setOf("co", "com", "org", "net", "ac", "gov", "edu", "ne", "or")

    /** "accounts.google.com" and "mail.google.com" both become "google.com"; "bbc.co.uk" stays. */
    fun baseDomain(host: String): String {
        val labels = host.lowercase().trim('.').removePrefix("www.").split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val take = if (labels.last().length == 2 && labels[labels.size - 2] in secondLevelSuffixes) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }

    /** Same site: the same base domain. Never true for an empty domain. */
    fun sameSite(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return baseDomain(a) == baseDomain(b)
    }

    /** The meaningful words of an app's package name, like "instagram" for com.instagram.android. */
    fun packageWords(packageName: String): List<String> =
        packageName.lowercase().split('.', '_').filter { it.length >= 3 && it !in genericParts }

    /**
     * Logins that fit, best first: for a website, the same site (exact host first); for an app,
     * logins whose website or name contains a word of the app's package name. These are only
     * suggestions: the user always picks or confirms the login.
     */
    fun match(candidates: List<MatchCandidate>, webDomain: String?, packageName: String): List<MatchCandidate> {
        val domain = webDomain?.lowercase()?.removePrefix("www.").orEmpty()
        if (domain.isNotEmpty()) {
            return candidates
                .filter { sameSite(it.domain, domain) }
                .sortedWith(compareByDescending<MatchCandidate> { it.domain == domain }.thenBy { it.serviceName.lowercase() })
        }
        val words = packageWords(packageName)
        if (words.isEmpty()) return emptyList()
        return candidates.filter { candidate ->
            val siteWord = baseDomain(candidate.domain).substringBefore('.')
            val name = candidate.serviceName.lowercase().filter { it.isLetterOrDigit() }
            words.any { word -> (siteWord.isNotEmpty() && siteWord == word) || (name.isNotEmpty() && (name == word || name.contains(word))) }
        }.sortedBy { it.serviceName.lowercase() }
    }

    /** What to call a new login saved from an app with no website: "Instagram" for com.instagram.android. */
    fun nameForPackage(packageName: String): String =
        packageWords(packageName).firstOrNull()?.replaceFirstChar { it.uppercase() } ?: packageName
}
