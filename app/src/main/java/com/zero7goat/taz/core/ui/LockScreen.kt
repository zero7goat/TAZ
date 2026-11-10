package com.zero7goat.taz.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.zero7goat.taz.core.security.AppLockManager
import com.zero7goat.taz.core.security.AppReset
import com.zero7goat.taz.core.security.BiometricAuth
import com.zero7goat.taz.core.security.BiometricAvailability
import com.zero7goat.taz.core.security.UnlockResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "m:ss" for a countdown. */
fun formatCountdown(millis: Long): String {
    val totalSeconds = ((millis + 999) / 1000).coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/**
 * Shown instead of the app while it is locked. The passcode always works; biometrics are an
 * optional shortcut. A forgotten passcode has one recovery: erasing all TAZ data.
 */
@Composable
fun LockScreen(lock: AppLockManager, biometricEnabled: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val pin = remember { TextFieldState() }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showForgot by remember { mutableStateOf(false) }

    val biometricReady = remember(context, biometricEnabled) {
        biometricEnabled && activity != null &&
            BiometricAuth.availability(context) == BiometricAvailability.AVAILABLE
    }
    val remainingMillis = (lockedUntil - now).coerceAtLeast(0L)
    val lockedOut = remainingMillis > 0

    fun promptBiometric() {
        val host = activity ?: return
        BiometricAuth.authenticate(
            activity = host,
            title = "Unlock TAZ",
            subtitle = null,
            negativeButtonText = "Use passcode",
            onSuccess = { lock.unlockWithBiometric() },
            onFailure = { }
        )
    }

    LaunchedEffect(Unit) { lockedUntil = lock.lockoutUntil() }
    LaunchedEffect(lockedUntil) {
        while (System.currentTimeMillis() < lockedUntil) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    // Offer the biometric prompt as soon as the screen is in the foreground.
    LaunchedEffect(biometricReady) {
        if (biometricReady) lifecycleOwner.lifecycle.withResumed { promptBiometric() }
    }

    fun submit() {
        if (busy || lockedOut) return
        val entered = pin.text.toString().toCharArray()
        pin.replaceAllText("")
        if (entered.isEmpty()) return
        busy = true
        message = null
        scope.launch {
            val result = lock.unlock(entered)
            busy = false
            when (result) {
                UnlockResult.Success -> Unit // the gate swaps this screen for the app
                is UnlockResult.Wrong -> {
                    message = if (result.attemptsBeforeDelay > 0) {
                        "Incorrect passcode. ${result.attemptsBeforeDelay} more tries before a short wait."
                    } else {
                        "Incorrect passcode."
                    }
                }
                is UnlockResult.LockedOut -> {
                    lockedUntil = System.currentTimeMillis() + result.remainingMillis
                    message = null
                }
                UnlockResult.Unreadable -> {
                    message = "This passcode can't be checked on this device. See \"Forgot passcode?\"."
                }
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            // Scrolls on small screens and when the keyboard is open; centred on large ones.
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TazLogo(height = 104.dp, contentDescription = "TAZ")
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "TAZ is locked",
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(32.dp))
                PinField(state = pin, label = "Passcode")

                // Reserved height: a message appearing or disappearing never moves the controls below it.
                val status = when {
                    lockedOut -> "Too many attempts. Try again in ${formatCountdown(remainingMillis)}."
                    else -> message
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (status != null) {
                        Text(
                            text = status,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Button(
                    onClick = { submit() },
                    enabled = !busy && !lockedOut,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Unlock") }
                Spacer(Modifier.height(4.dp))
                if (biometricReady) {
                    TextButton(onClick = { promptBiometric() }) { Text("Use biometrics") }
                }
                TextButton(onClick = { showForgot = true }) { Text("Forgot passcode?") }
            }
        }
    }

    if (showForgot) {
        ForgotPasscodeDialog(
            onErase = {
                scope.launch {
                    AppReset.eraseEverything(context)
                    activity?.let { AppReset.restart(it) }
                }
            },
            onDismiss = { showForgot = false }
        )
    }
}

/** The only recovery for a forgotten passcode: erase everything. Needs the word ERASE typed in. */
@Composable
private fun ForgotPasscodeDialog(onErase: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forgot your passcode?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "TAZ keeps everything on this device and has no account, so a forgotten " +
                        "passcode can't be recovered. You can erase all TAZ data (notes, " +
                        "passwords, files and settings) and start again. This can't be undone."
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Type ERASE to confirm") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onErase, enabled = typed.trim() == "ERASE") { Text("Erase everything") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
