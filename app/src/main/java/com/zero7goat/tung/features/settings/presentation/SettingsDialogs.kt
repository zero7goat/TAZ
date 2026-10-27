package com.zero7goat.tung.features.settings.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zero7goat.tung.core.security.PasscodeRules
import com.zero7goat.tung.core.security.UnlockResult
import com.zero7goat.tung.core.ui.PinField
import com.zero7goat.tung.core.ui.formatCountdown
import kotlinx.coroutines.launch

/** A list of choices with one selected. Picking a choice applies it and closes the dialog. */
@Composable
fun <T> OptionDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    familyFor: (T) -> FontFamily? = { null }
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = familyFor(value) ?: MaterialTheme.typography.bodyLarge.fontFamily
                            )
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** What to do with the app lock when it is already on. */
@Composable
fun LockOptionsDialog(onChange: () -> Unit, onTurnOff: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App lock") },
        text = { Text("TAZ asks for your passcode when it opens and after the auto-lock time.") },
        confirmButton = { TextButton(onClick = onChange) { Text("Change passcode") } },
        dismissButton = { TextButton(onClick = onTurnOff) { Text("Turn off") } }
    )
}

/** First-time passcode: asks twice, 4 to 16 digits. */
@Composable
fun SetPasscodeDialog(
    onSave: suspend (CharArray) -> Boolean,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Set a passcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Choose 4 to 16 digits. TAZ can't recover a forgotten passcode, so pick one you will remember.",
                    style = MaterialTheme.typography.bodyMedium
                )
                PinField(state = first, label = "New passcode")
                PinField(state = second, label = "Repeat passcode")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val a = first.text.toString().toCharArray()
                    val b = second.text.toString().toCharArray()
                    val matches = a.contentEquals(b)
                    b.fill('\u0000')
                    when {
                        !PasscodeRules.isValid(a) -> {
                            a.fill('\u0000')
                            error = "Use 4 to 16 digits."
                        }
                        !matches -> {
                            a.fill('\u0000')
                            error = "The passcodes don't match."
                        }
                        else -> {
                            busy = true
                            scope.launch {
                                val saved = onSave(a)
                                busy = false
                                if (saved) onSaved() else error = "Couldn't save the passcode."
                            }
                        }
                    }
                }
            ) { Text("Turn on") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

/** Change the passcode: current, then new twice. */
@Composable
fun ChangePasscodeDialog(
    onChange: suspend (current: CharArray, new: CharArray) -> UnlockResult,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val current = remember { TextFieldState() }
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change passcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PinField(state = current, label = "Current passcode")
                PinField(state = first, label = "New passcode")
                PinField(state = second, label = "Repeat new passcode")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val old = current.text.toString().toCharArray()
                    val a = first.text.toString().toCharArray()
                    val b = second.text.toString().toCharArray()
                    val matches = a.contentEquals(b)
                    b.fill('\u0000')
                    when {
                        !PasscodeRules.isValid(a) -> {
                            old.fill('\u0000')
                            a.fill('\u0000')
                            error = "The new passcode must be 4 to 16 digits."
                        }
                        !matches -> {
                            old.fill('\u0000')
                            a.fill('\u0000')
                            error = "The new passcodes don't match."
                        }
                        else -> {
                            busy = true
                            scope.launch {
                                val result = onChange(old, a)
                                busy = false
                                if (result == UnlockResult.Success) onChanged() else error = result.failureText()
                            }
                        }
                    }
                }
            ) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

/** Turning the lock off needs the current passcode. */
@Composable
fun TurnOffLockDialog(
    onRemove: suspend (CharArray) -> UnlockResult,
    onRemoved: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val current = remember { TextFieldState() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Turn off app lock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter your passcode to turn the app lock off.", style = MaterialTheme.typography.bodyMedium)
                PinField(state = current, label = "Passcode")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val entered = current.text.toString().toCharArray()
                    if (entered.isEmpty()) {
                        error = "Enter your passcode."
                    } else {
                        busy = true
                        scope.launch {
                            val result = onRemove(entered)
                            busy = false
                            if (result == UnlockResult.Success) onRemoved() else error = result.failureText()
                        }
                    }
                }
            ) { Text("Turn off") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

private fun UnlockResult.failureText(): String = when (this) {
    UnlockResult.Success -> ""
    is UnlockResult.Wrong -> "That passcode is incorrect."
    is UnlockResult.LockedOut -> "Too many attempts. Try again in ${formatCountdown(remainingMillis)}."
    UnlockResult.Unreadable -> "The saved passcode can't be checked on this device."
}
