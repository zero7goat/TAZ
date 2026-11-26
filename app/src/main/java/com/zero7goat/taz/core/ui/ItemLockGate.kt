package com.zero7goat.taz.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zero7goat.taz.core.AppContainer
import com.zero7goat.taz.core.security.BiometricAuth
import com.zero7goat.taz.core.security.BiometricAvailability
import com.zero7goat.taz.core.security.UnlockResult
import kotlinx.coroutines.launch

/** The key an item's own lock is remembered under, like "LOGIN:1234". */
fun itemLockKey(type: String, id: String) = "$type:$id"

/**
 * Shows [content] only after the passcode (or biometrics) is entered again, when [locked] is on.
 * Once opened, the item stays open until TAZ locks. Without an app lock there is nothing to ask
 * for, so [content] is shown directly.
 */
@Composable
fun ItemLockGate(locked: Boolean, itemKey: String, title: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lock = remember(context) { AppContainer.lockManager(context) }
    val hasPasscode by lock.hasPasscode.collectAsStateWithLifecycle()
    val unlocked by lock.unlockedItems.collectAsStateWithLifecycle()
    if (!locked || !hasPasscode || itemKey in unlocked) {
        content()
        return
    }

    val scope = rememberCoroutineScope()
    val pin = remember { TextFieldState() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val activity = remember(context) { context.findFragmentActivity() }
    val settings by AppContainer.settings(context).settings.collectAsStateWithLifecycle(null)
    val biometric = settings?.biometricEnabled == true && activity != null &&
        BiometricAuth.availability(context) == BiometricAvailability.AVAILABLE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(TazIcons.Lock, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            "This item has its own lock. Enter your passcode to open it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Column(Modifier.widthIn(max = 360.dp)) {
            PinField(state = pin, label = "Passcode")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            enabled = !busy,
            onClick = {
                val entered = pin.text.toString().toCharArray()
                pin.edit { replace(0, length, "") }
                if (entered.isEmpty()) return@Button
                busy = true
                scope.launch {
                    val result = lock.verifyForAction(entered)
                    busy = false
                    when (result) {
                        UnlockResult.Success -> lock.markItemUnlocked(itemKey)
                        is UnlockResult.LockedOut -> error = "Too many attempts. Try again in ${formatCountdown(result.remainingMillis)}."
                        else -> error = "Incorrect passcode."
                    }
                }
            },
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Open") }
        if (biometric && activity != null) {
            TextButton(onClick = {
                BiometricAuth.authenticate(
                    activity = activity,
                    title = "Open $title",
                    subtitle = null,
                    negativeButtonText = "Use passcode",
                    onSuccess = { lock.markItemUnlocked(itemKey) },
                    onFailure = { }
                )
            }) { Text("Use biometrics") }
        }
    }
}
