package com.zero7goat.taz.features.passkeys

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import com.zero7goat.taz.core.AppContainer
import com.zero7goat.taz.core.security.AppLockManager
import com.zero7goat.taz.core.settings.AppSettings
import com.zero7goat.taz.core.ui.AppFonts
import com.zero7goat.taz.core.ui.AppLockGate
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.ui.theme.TAZTheme
import java.security.MessageDigest
import kotlinx.coroutines.launch

/**
 * Creates or uses a passkey after the user picked TAZ in Android's passkey sheet. The lock screen
 * comes first when TAZ is locked (that is the "user verification"); creating asks to confirm.
 * Screenshots are blocked.
 */
@RequiresApi(34)
class PasskeyActivity : FragmentActivity() {

    private lateinit var lock: AppLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lock = AppContainer.lockManager(applicationContext)
        val settingsRepository = AppContainer.settings(applicationContext)
        setResult(Activity.RESULT_CANCELED)
        enableEdgeToEdge()
        setContent {
            val settings by produceState<AppSettings?>(null) { settingsRepository.settings.collect { value = it } }
            val loaded = settings ?: return@setContent
            TAZTheme(themeMode = loaded.themeMode, fontFamily = AppFonts.familyFor(loaded.fontKey)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppLockGate(lock = lock, biometricEnabled = loaded.biometricEnabled, onLocked = {}) {
                        if (intent.action == ACTION_CREATE) CreateContent() else GetContent()
                    }
                }
            }
        }
    }

    @Composable
    private fun CreateContent() {
        val request = remember { PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) }
        val call = request?.callingRequest as? CreatePublicKeyCredentialRequest
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        if (request == null || call == null) {
            LaunchedEffect(Unit) { failCreate("Nothing to save") }
            return
        }
        val options = remember { runCatching { WebAuthn.parseCreation(call.requestJson) }.getOrNull() }
        Centered {
            Icon(TazIcons.Key, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Save a passkey?", style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(options?.rpId, options?.userName?.takeIf { it.isNotBlank() }).joinToString("\n"),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Text(
                "It stays on this phone, encrypted, and is included in your encrypted TAZ backups.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            Button(
                enabled = !busy && options != null,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val json = PasskeyRepository.create(applicationContext).create(
                                call.requestJson, originOf(request.callingAppInfo), request.callingAppInfo.packageName, call.clientDataHash
                            )
                            val result = Intent()
                            PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(json))
                            setResult(Activity.RESULT_OK, result)
                            finish()
                        } catch (e: PasskeyException) {
                            busy = false
                            error = e.message
                        } catch (e: Exception) {
                            failCreate("TAZ couldn't save this passkey")
                        }
                    }
                },
                modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Save passkey") }
            TextButton(onClick = { failCreate("Cancelled") }) { Text("Cancel") }
        }
    }

    @Composable
    private fun GetContent() {
        val request = remember { PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) }
        val option = request?.credentialOptions?.filterIsInstance<GetPublicKeyCredentialOption>()?.firstOrNull()
        val credentialId = intent.getStringExtra(EXTRA_CREDENTIAL_ID)
        var error by remember { mutableStateOf<String?>(null) }
        // TAZ is unlocked here (the gate asked for the passcode if needed): sign straight away.
        LaunchedEffect(Unit) {
            if (request == null || option == null || credentialId == null) {
                failGet("Nothing to sign in with")
                return@LaunchedEffect
            }
            try {
                val json = PasskeyRepository.create(applicationContext).authenticate(
                    credentialId, option.requestJson, originOf(request.callingAppInfo), request.callingAppInfo.packageName, option.clientDataHash
                )
                val result = Intent()
                PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(PublicKeyCredential(json)))
                setResult(Activity.RESULT_OK, result)
                finish()
            } catch (e: PasskeyException) {
                error = e.message
            } catch (e: Exception) {
                failGet("TAZ couldn't sign in with this passkey")
            }
        }
        Centered {
            if (error == null) CircularProgressIndicator() else {
                Text(error!!, textAlign = TextAlign.Center)
                TextButton(onClick = { failGet(error!!) }) { Text("Close") }
            }
        }
    }

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
        ) { content() }
    }

    /**
     * The origin to put in the client data. Browsers send their own client data hash (then the
     * origin is not used); for an app it is "android:apk-key-hash:" + the SHA-256 of its signing
     * certificate, which the website checks against the apps it trusts.
     */
    private fun originOf(info: CallingAppInfo): String {
        val signer = info.signingInfo.apkContentsSigners.firstOrNull()?.toByteArray() ?: ByteArray(0)
        return "android:apk-key-hash:" + B64.url(MessageDigest.getInstance("SHA-256").digest(signer))
    }

    private fun failCreate(message: String) {
        val result = Intent()
        PendingIntentHandler.setCreateCredentialException(result, CreateCredentialUnknownException(message))
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun failGet(message: String) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(result, GetCredentialUnknownException(message))
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    override fun onStart() {
        super.onStart()
        lock.onAppStarted()
    }

    override fun onStop() {
        if (!isChangingConfigurations) lock.onAppStopped()
        super.onStop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lock.onUserInteraction()
    }

    companion object {
        const val ACTION_CREATE = "com.zero7goat.taz.passkey.CREATE"
        const val ACTION_GET = "com.zero7goat.taz.passkey.GET"
        const val EXTRA_CREDENTIAL_ID = "com.zero7goat.taz.passkey.CREDENTIAL_ID"

        @Suppress("unused")
        private val supported = Build.VERSION.SDK_INT >= 34
    }
}
