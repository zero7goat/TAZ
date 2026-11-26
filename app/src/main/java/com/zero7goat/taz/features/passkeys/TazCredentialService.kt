package com.zero7goat.taz.features.passkeys

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.zero7goat.taz.core.security.VaultMode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TAZ as a passkey provider (Android 14+: Settings → Passwords & accounts → TAZ). Android asks
 * which passkeys TAZ has for a site; TAZ answers with names only. Creating or using a passkey
 * always goes through [PasskeyActivity], which asks for the passcode first when TAZ is locked.
 */
@RequiresApi(34)
class TazCredentialService : CredentialProviderService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val codes = AtomicInteger(100)

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            callback.onError(CreateCredentialUnknownException("TAZ only stores passkeys here"))
            return
        }
        val intent = Intent(this, PasskeyActivity::class.java).setAction(PasskeyActivity.ACTION_CREATE)
        val entry = CreateEntry.Builder("TAZ", pending(intent))
            .setDescription("Saved in TAZ on this phone, encrypted")
            .build()
        callback.onResult(BeginCreateCredentialResponse.Builder().addCreateEntry(entry).build())
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        // The decoy vault never offers the real vault's passkeys.
        if (VaultMode.isDecoy) {
            callback.onResult(BeginGetCredentialResponse())
            return
        }
        val job = scope.launch {
            try {
                val repository = PasskeyRepository.create(applicationContext)
                val builder = BeginGetCredentialResponse.Builder()
                request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>().forEach { option ->
                    val parsed = runCatching { WebAuthn.parseRequest(option.requestJson) }.getOrNull() ?: return@forEach
                    repository.candidates(parsed.rpId, parsed.allowCredentialIds).forEach { passkey ->
                        val intent = Intent(this@TazCredentialService, PasskeyActivity::class.java)
                            .setAction(PasskeyActivity.ACTION_GET)
                            .putExtra(PasskeyActivity.EXTRA_CREDENTIAL_ID, passkey.credentialId)
                        builder.addCredentialEntry(
                            PublicKeyCredentialEntry.Builder(this@TazCredentialService, passkey.userName.ifBlank { passkey.rpId }, pending(intent), option)
                                .setDisplayName(passkey.userDisplayName.ifBlank { null })
                                .build()
                        )
                    }
                }
                callback.onResult(builder.build())
            } catch (e: Exception) {
                callback.onError(GetCredentialUnknownException("TAZ couldn't look up passkeys"))
            }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        // TAZ keeps no sign-in session state to clear.
        callback.onResult(null)
    }

    private fun pending(intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            this, codes.getAndIncrement(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
