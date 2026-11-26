package com.zero7goat.taz.features.autofill

import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.widget.Toast
import android.widget.inline.InlinePresentationSpec
import com.zero7goat.taz.core.AppContainer
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TAZ as Android's autofill service. Runs on the device only: Android hands TAZ the structure of
 * the screen, TAZ answers with suggestions. No password leaves TAZ until the user picks a login,
 * and when TAZ is locked every suggestion opens the lock screen first.
 */
class TazAutofillService : AutofillService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        val form = structure?.let { FormParser.parse(it) }
        if (form == null || form.packageName == packageName || (!form.isLoginForm && form.otpIds.isEmpty())) {
            callback.onSuccess(null)
            return
        }
        // Keyboard-strip chips (Android 11+), when the keyboard asked for them.
        val inlineSpecs: List<InlinePresentationSpec> =
            if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty() else emptyList()
        val job = scope.launch {
            val response = try {
                if (form.isLoginForm) buildLoginResponse(form, inlineSpecs) else buildCodeResponse(form, inlineSpecs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // never show half-built suggestions; and never include details of the failure
            }
            callback.onSuccess(response)
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private fun specAt(specs: List<InlinePresentationSpec>, index: Int): InlinePresentationSpec? =
        if (specs.isEmpty()) null else specs[minOf(index, specs.lastIndex)]

    private suspend fun buildLoginResponse(form: ParsedForm, specs: List<InlinePresentationSpec>): FillResponse {
        val unlocked = AppContainer.lockManager(applicationContext).isUnlockedForAutofill()
        val where = form.webDomain ?: AutofillMatcher.nameForPackage(form.packageName)
        val builder = FillResponse.Builder()
        var index = 0
        if (unlocked) {
            // Names and usernames only; the password is read once the user picks one.
            val vault = AutofillVault(applicationContext)
            vault.matches(vault.all(), form.webDomain, form.packageName).take(MAX_SUGGESTIONS).forEach { entry ->
                builder.addDataset(
                    AutofillDatasets.locked(
                        this, form, entry.serviceName, entry.username.ifEmpty { entry.domain }, entry.id,
                        inlineSpec = specAt(specs, index++)
                    )
                )
            }
        }
        builder.addDataset(
            AutofillDatasets.locked(
                this, form,
                title = if (unlocked) "Search TAZ" else "Unlock TAZ to fill",
                subtitle = where,
                credentialId = null,
                inlineSpec = specAt(specs, index)
            )
        )
        if (form.passwordIds.isNotEmpty()) {
            val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or
                (if (form.usernameIds.isNotEmpty()) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
            builder.setSaveInfo(
                SaveInfo.Builder(type, form.passwordIds.toTypedArray())
                    .apply { if (form.usernameIds.isNotEmpty()) setOptionalIds(form.usernameIds.toTypedArray()) }
                    // Web pages often stay on screen after signing in; offer to save when the fields go away.
                    .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                    .build()
            )
        }
        return builder.build()
    }

    /** A "enter your code" screen right after TAZ filled a login here: offer that login's code. */
    private fun buildCodeResponse(form: ParsedForm, specs: List<InlinePresentationSpec>): FillResponse? {
        val credentialId = RecentFills.recent(form.webDomain, form.packageName) ?: return null
        return FillResponse.Builder()
            .addDataset(
                AutofillDatasets.locked(
                    this, form, "Two-factor code from TAZ", "", credentialId,
                    mode = PickMode.CODE, inlineSpec = specAt(specs, 0)
                )
            )
            .build()
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val forms = request.fillContexts.map { FormParser.parse(it.structure) }
        val last = forms.lastOrNull()
        // Sign-ins split over two screens: the username may only be on the first one.
        val username = forms.map { it.usernameValue }.lastOrNull { it.isNotBlank() }.orEmpty()
        val password = forms.map { it.passwordValue }.lastOrNull { it.isNotEmpty() }.orEmpty()
        if (last == null || password.isEmpty() || last.packageName == packageName) {
            callback.onSuccess()
            return
        }
        scope.launch {
            try {
                val outcome = AutofillSaver(AutofillVault(applicationContext))
                    .save(last.webDomain, last.packageName, username, password)
                val message = when (outcome) {
                    SaveOutcome.CREATED -> "Login saved to TAZ"
                    SaveOutcome.UPDATED -> "Password updated in TAZ. The old one is kept in the login."
                    SaveOutcome.UNCHANGED -> "This login is already in TAZ"
                }
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                callback.onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                callback.onFailure("TAZ couldn't save this login")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val MAX_SUGGESTIONS = 5
    }
}
