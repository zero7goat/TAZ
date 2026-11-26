package com.zero7goat.taz.features.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId

/**
 * The login form found on screen: which boxes take the username and password, the website (for
 * browsers) and the app. Values are read only for saving and live only in memory.
 */
class ParsedForm(
    val packageName: String,
    val webDomain: String?,
    val usernameIds: List<AutofillId>,
    val passwordIds: List<AutofillId>,
    val otpIds: List<AutofillId>,
    val usernameValue: String,
    val passwordValue: String
) {
    val fillableIds: List<AutofillId> get() = usernameIds + passwordIds
    val isLoginForm: Boolean get() = passwordIds.isNotEmpty() || usernameIds.isNotEmpty()

    override fun toString(): String = "ParsedForm(package=$packageName, values=hidden)"
}

object FormParser {

    fun parse(structure: AssistStructure): ParsedForm {
        val usernames = mutableListOf<Pair<AutofillId, String>>()
        val passwords = mutableListOf<Pair<AutofillId, String>>()
        val otps = mutableListOf<AutofillId>()
        // The last plain text box seen before a password box: on many forms that is the username.
        var lastText: Pair<AutofillId, String>? = null
        var guessedUsername: Pair<AutofillId, String>? = null
        var webDomain: String? = null

        fun visit(node: AssistStructure.ViewNode) {
            if (webDomain == null) node.webDomain?.takeIf { it.isNotBlank() }?.let { webDomain = it }
            val id = node.autofillId
            if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT &&
                (android.os.Build.VERSION.SDK_INT < 28 || node.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_NO) &&
                node.visibility == View.VISIBLE
            ) {
                val html = node.htmlInfo
                val attrs = html?.attributes.orEmpty().associate { (it.first ?: "").lowercase() to (it.second ?: "") }
                val traits = FieldTraits(
                    autofillHints = node.autofillHints?.toList().orEmpty(),
                    inputType = node.inputType,
                    htmlTag = html?.tag.orEmpty(),
                    htmlType = attrs["type"].orEmpty(),
                    htmlAutocomplete = attrs["autocomplete"].orEmpty(),
                    htmlName = listOf(attrs["name"], attrs["id"]).filterNotNull().joinToString(" "),
                    idEntry = node.idEntry.orEmpty(),
                    hint = node.hint.orEmpty()
                )
                val value = node.autofillValue?.takeIf { it.isText }?.textValue?.toString().orEmpty()
                when (FieldClassifier.classify(traits)) {
                    FieldKind.PASSWORD -> {
                        passwords += id to value
                        if (usernames.isEmpty() && guessedUsername == null) guessedUsername = lastText
                    }
                    FieldKind.USERNAME -> usernames += id to value
                    FieldKind.OTP -> otps += id
                    FieldKind.OTHER -> if (FieldClassifier.isPlainTextInput(traits)) lastText = id to value
                }
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }

        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)

        if (usernames.isEmpty()) guessedUsername?.let { usernames += it }
        return ParsedForm(
            packageName = structure.activityComponent?.packageName.orEmpty(),
            webDomain = webDomain?.lowercase()?.removePrefix("www."),
            usernameIds = usernames.map { it.first }.take(2),
            passwordIds = passwords.map { it.first }.take(2),
            otpIds = otps.take(1),
            usernameValue = usernames.firstOrNull { it.second.isNotBlank() }?.second.orEmpty(),
            passwordValue = passwords.firstOrNull { it.second.isNotEmpty() }?.second.orEmpty()
        )
    }
}
