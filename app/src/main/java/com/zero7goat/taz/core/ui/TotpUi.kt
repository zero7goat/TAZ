package com.zero7goat.taz.core.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.CustomFields
import com.zero7goat.taz.core.totp.QrDecoder
import com.zero7goat.taz.core.totp.QrScannerDialog
import com.zero7goat.taz.core.totp.Totp
import com.zero7goat.taz.core.totp.TotpSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The current time, updated at the start of every second while shown. */
@Composable
fun rememberTickingTime(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    return now
}

/**
 * A two-factor field in view mode: the live code in large digits, a ring showing how long it is
 * valid, and a copy button. [onCopyCode] receives the code (never the setup key).
 */
@Composable
fun TotpCodeView(field: CustomField, onCopyCode: (String) -> Unit) {
    val spec = remember(field.value) { Totp.parse(field.value) }
    FieldSurface(innerEndPadding = 2.dp) {
        if (spec == null) {
            Text(
                "This setup key isn't valid. Edit the login to fix it.",
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LiveCode(spec, onCopyCode)
        }
    }
}

@Composable
private fun LiveCode(spec: TotpSpec, onCopyCode: (String) -> Unit) {
    val now = rememberTickingTime()
    val code = remember(spec, now / 1000 / spec.periodSeconds) { Totp.code(spec, now) }
    val left = Totp.secondsLeft(spec, now)
    val progress by animateFloatAsState(left / spec.periodSeconds.toFloat(), tween(400), label = "totp")
    val ending = left <= 5
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = Totp.group(code),
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            color = if (ending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        )
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
                color = if (ending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            Text(left.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onCopyCode(code) }) {
            Icon(TazIcons.Copy, contentDescription = "Copy code", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The setup-key input of a two-factor field: type or paste the key (or otpauth link), or scan the
 * QR code with the camera or from a screenshot. Once the key is valid the current code is shown,
 * so it can be checked against the website before saving.
 */
@Composable
fun TotpFieldInput(
    field: CustomField,
    revealed: Boolean,
    onChange: (CustomField) -> Unit,
    onToggleReveal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun accept(text: String?) {
        val spec = text?.let { Totp.parse(it) }
        if (text == null || spec == null) {
            problem = "That isn't a two-factor QR code. Look for the code on the site's two-factor setup page."
            return
        }
        problem = null
        val label = field.label.ifBlank { spec.issuer.ifBlank { "Two-factor code" } }
        onChange(field.copy(label = label, value = text.trim()))
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else problem = "Camera access was not allowed. You can pick a screenshot instead."
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.Default) {
                    try {
                        QrDecoder.decodeImage(context, uri)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (text == null) problem = "No QR code was found in that picture." else accept(text)
            }
        }
    }

    Column {
        FieldSurface(innerEndPadding = 2.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (field.value.isEmpty()) {
                        Text(
                            "Setup key or scan a QR code",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    BasicTextField(
                        value = field.value,
                        onValueChange = { problem = null; onChange(field.copy(value = it.take(CustomFields.MAX_VALUE_LENGTH))) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.Characters
                        )
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(TazIcons.QrScan, contentDescription = "Scan a QR code", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Scan with camera") },
                            leadingIcon = { Icon(TazIcons.Camera, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                problem = null
                                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (granted) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Read from a screenshot") },
                            leadingIcon = { Icon(TazIcons.Image, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                problem = null
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                    }
                }
                if (field.value.isNotEmpty()) {
                    IconButton(onClick = onToggleReveal) {
                        Icon(
                            if (revealed) TazIcons.EyeOff else TazIcons.Eye,
                            contentDescription = if (revealed) "Hide setup key" else "Show setup key",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        val spec = remember(field.value) { Totp.parse(field.value) }
        when {
            problem != null -> HelpText(problem!!, error = true)
            spec != null -> {
                val now = rememberTickingTime()
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(TazIcons.Timer, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Current code ${Totp.group(Totp.code(spec, now))}. Check it matches before saving.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (scanning) {
        QrScannerDialog(
            onResult = { text -> scanning = false; accept(text) },
            onDismiss = { scanning = false }
        )
    }
}

@Composable
private fun HelpText(text: String, error: Boolean) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 4.dp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
}
