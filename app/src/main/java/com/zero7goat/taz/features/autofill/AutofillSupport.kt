package com.zero7goat.taz.features.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.slice.Slice
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.drawable.Icon
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.InlinePresentation
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.zero7goat.taz.R
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.fields.FieldType
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import com.zero7goat.taz.core.totp.Totp
import com.zero7goat.taz.features.passwords.data.CredentialRepository
import com.zero7goat.taz.features.passwords.data.DetailResult
import com.zero7goat.taz.features.passwords.domain.Categories
import com.zero7goat.taz.features.passwords.domain.CredentialDetail
import com.zero7goat.taz.features.passwords.domain.CredentialInput
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first

/** A login offered by autofill: what the list shows. The password is only read once one is picked. */
data class AutofillEntry(
    val id: String,
    val serviceName: String,
    val domain: String,
    val username: String,
    /** Has its own lock: the username is not shown and the passcode is asked before filling. */
    val isLocked: Boolean = false
)

/** Reads logins for autofill. Only logins outside the trash. */
class AutofillVault(context: Context) {

    private val database = TazDatabase.getInstance(context.applicationContext)
    val repository = CredentialRepository(database.credentialDao(), KeystoreFieldCipher())

    suspend fun all(): List<AutofillEntry> =
        repository.observeListRows().first().map { row ->
            AutofillEntry(row.id, row.serviceName, row.domain, if (row.isLocked) "" else repository.usernameFor(row), row.isLocked)
        }

    /**
     * Logins that fit, best first: for an app, the ones picked for it before come first, then
     * those whose name or website fits the app's package name.
     */
    suspend fun matches(entries: List<AutofillEntry>, webDomain: String?, packageName: String): List<AutofillEntry> {
        val byId = entries.associateBy { it.id }
        val linked = if (webDomain.isNullOrBlank() && packageName.isNotBlank()) {
            database.appLinkDao().credentialIdsFor(packageName).mapNotNull { byId[it] }
        } else {
            emptyList()
        }
        val guessed = AutofillMatcher.match(entries.map { MatchCandidate(it.id, it.serviceName, it.domain) }, webDomain, packageName)
            .mapNotNull { byId[it.id] }
        return (linked + guessed).distinctBy { it.id }
    }

    /** Remembers that [credentialId] was picked in the app [packageName]. */
    suspend fun remember(packageName: String, credentialId: String) {
        if (packageName.isBlank()) return
        database.appLinkDao().upsert(AppLinkEntity(packageName, credentialId, System.currentTimeMillis()))
    }

    suspend fun detail(id: String): CredentialDetail? = (repository.getDetail(id) as? DetailResult.Found)?.detail
}

/**
 * Which login was just filled where, so the two-factor box on the next screen can get that
 * login's code. In memory only, forgotten after a few minutes.
 */
object RecentFills {
    private const val KEEP_MS = 10 * 60 * 1000L
    private val fills = ConcurrentHashMap<String, Pair<String, Long>>()

    private fun key(webDomain: String?, packageName: String) =
        webDomain?.takeIf { it.isNotBlank() }?.let { AutofillMatcher.baseDomain(it) } ?: packageName

    fun record(webDomain: String?, packageName: String, credentialId: String) {
        fills[key(webDomain, packageName)] = credentialId to System.currentTimeMillis()
    }

    fun recent(webDomain: String?, packageName: String): String? {
        val (id, at) = fills[key(webDomain, packageName)] ?: return null
        return if (System.currentTimeMillis() - at <= KEEP_MS) id else null
    }

    fun clear() = fills.clear()
}

/** The current two-factor code of [detail], or null if it has no (valid) two-factor field. */
fun currentCode(detail: CredentialDetail): String? =
    detail.fields.firstOrNull { it.type == FieldType.TOTP }?.let { Totp.parse(it.value) }
        ?.let { Totp.code(it, System.currentTimeMillis()) }

/** Builds what Android shows in its autofill list and keyboard strip. */
@Suppress("DEPRECATION") // The pre-Android 13 Dataset API is the one available back to Android 8.
object AutofillDatasets {

    private val requestCodes = AtomicInteger(1)

    fun presentation(context: Context, title: String, subtitle: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle)
            setViewVisibility(R.id.autofill_subtitle, if (subtitle.isEmpty()) View.GONE else View.VISIBLE)
        }

    /**
     * A suggestion whose values are only produced after TAZ's picker (and lock) has run.
     * [inlineSpec] adds a keyboard-strip chip on Android 11+ when the keyboard asked for one.
     */
    fun locked(
        context: Context,
        form: ParsedForm,
        title: String,
        subtitle: String,
        credentialId: String?,
        mode: PickMode = PickMode.LOGIN,
        inlineSpec: InlinePresentationSpec? = null
    ): Dataset {
        val builder = Dataset.Builder(presentation(context, title, subtitle))
        val ids = if (mode == PickMode.CODE) form.otpIds else form.fillableIds
        ids.forEach { builder.setValue(it, null) }
        if (inlineSpec != null && Build.VERSION.SDK_INT >= 30) {
            inline(context, inlineSpec, title, subtitle)?.let { builder.setInlinePresentation(it) }
        }
        builder.setAuthentication(pickerSender(context, form, credentialId, mode))
        return builder.build()
    }

    /** The filled-in values for [detail], plus its two-factor code when the form has a code box. */
    fun filled(
        context: Context,
        usernameIds: List<AutofillId>,
        passwordIds: List<AutofillId>,
        otpIds: List<AutofillId>,
        detail: CredentialDetail
    ): Dataset {
        val builder = Dataset.Builder(presentation(context, detail.serviceName, detail.username))
        usernameIds.forEach { builder.setValue(it, AutofillValue.forText(detail.username)) }
        passwordIds.forEach { builder.setValue(it, AutofillValue.forText(detail.password)) }
        val code = currentCode(detail)
        if (code != null) otpIds.forEach { builder.setValue(it, AutofillValue.forText(code)) }
        return builder.build()
    }

    /** Only the two-factor code of [detail], for a "enter the code" screen. Null without a code. */
    fun codeOnly(context: Context, otpIds: List<AutofillId>, detail: CredentialDetail): Dataset? {
        val code = currentCode(detail) ?: return null
        if (otpIds.isEmpty()) return null
        val builder = Dataset.Builder(presentation(context, "Two-factor code", detail.serviceName))
        otpIds.forEach { builder.setValue(it, AutofillValue.forText(code)) }
        return builder.build()
    }

    @RequiresApi(30)
    @SuppressLint("RestrictedApi")
    private fun inline(context: Context, spec: InlinePresentationSpec, title: String, subtitle: String): InlinePresentation? {
        val style = spec.style
        if (!UiVersions.getVersions(style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        // Long-pressing a chip shows this; it opens nothing sensitive.
        val attribution = PendingIntent.getActivity(
            context, 0, Intent(context, com.zero7goat.taz.TazActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val slice: Slice = InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle(title)
            .apply { if (subtitle.isNotEmpty()) setSubtitle(subtitle) }
            .setStartIcon(Icon.createWithResource(context, R.mipmap.taz_launcher_round))
            .build()
            .slice
        return InlinePresentation(slice, spec, false)
    }

    private fun pickerSender(context: Context, form: ParsedForm, credentialId: String?, mode: PickMode): IntentSender {
        val intent = Intent(context, AutofillPickerActivity::class.java).apply {
            putParcelableArrayListExtra(AutofillPickerActivity.EXTRA_USERNAME_IDS, ArrayList(form.usernameIds))
            putParcelableArrayListExtra(AutofillPickerActivity.EXTRA_PASSWORD_IDS, ArrayList(form.passwordIds))
            putParcelableArrayListExtra(AutofillPickerActivity.EXTRA_OTP_IDS, ArrayList(form.otpIds))
            putExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN, form.webDomain)
            putExtra(AutofillPickerActivity.EXTRA_PACKAGE, form.packageName)
            putExtra(AutofillPickerActivity.EXTRA_CREDENTIAL_ID, credentialId)
            putExtra(AutofillPickerActivity.EXTRA_MODE, mode.name)
        }
        // Mutable: Android adds the screen structure to this explicit intent when it starts the picker.
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getActivity(context, requestCodes.getAndIncrement(), intent, flags).intentSender
    }
}

/** What the picker hands back: a whole login, or just the two-factor code of one. */
enum class PickMode { LOGIN, CODE }

enum class SaveOutcome { CREATED, UPDATED, UNCHANGED }

/** Saves a login that the user just typed into another app, after Android asked "Save to TAZ?". */
class AutofillSaver(private val vault: AutofillVault) {

    suspend fun save(webDomain: String?, packageName: String, username: String, password: String): SaveOutcome {
        val existing = vault.matches(vault.all(), webDomain, packageName)
            .firstOrNull { it.username.equals(username.trim(), ignoreCase = true) }
            ?.let { vault.detail(it.id) }
        if (existing != null) {
            if (existing.password == password) return SaveOutcome.UNCHANGED
            // The old password goes into the login's password history (see CredentialRepository.save).
            vault.repository.save(
                CredentialInput(
                    id = existing.id, folderId = existing.folderId, serviceName = existing.serviceName,
                    username = existing.username, password = password, websiteUrl = existing.websiteUrl,
                    notes = existing.notes, isFavorite = existing.isFavorite, createdAt = existing.createdAt,
                    category = existing.category,
                    fields = existing.fields
                )
            )
            return SaveOutcome.UPDATED
        }
        val id = UUID.randomUUID().toString()
        vault.repository.save(
            CredentialInput(
                id = id,
                folderId = null,
                serviceName = webDomain?.takeIf { it.isNotBlank() } ?: AutofillMatcher.nameForPackage(packageName),
                username = username.trim(),
                password = password,
                websiteUrl = webDomain?.takeIf { it.isNotBlank() }?.let { "https://$it" }.orEmpty(),
                notes = "",
                isFavorite = false,
                createdAt = 0L,
                category = Categories.LOGIN.key
            )
        )
        if (webDomain.isNullOrBlank()) vault.remember(packageName, id)
        return SaveOutcome.CREATED
    }

}

