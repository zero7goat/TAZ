package com.zero7goat.taz.core.transfer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.zero7goat.taz.core.totp.QrDecoder
import com.zero7goat.taz.core.totp.QrScannerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Draws [text] as a QR code bitmap, on the device. */
fun qrBitmap(text: String, size: Int = 900): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2)
    )
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** Shows one login as an encrypted QR code plus the one-time code needed to open it. */
@Composable
fun ShareLoginQrDialog(login: SharedLogin, onDismiss: () -> Unit) {
    val code = remember { QrShare.newCode() }
    val bitmap by produceState<Bitmap?>(null) {
        value = withContext(Dispatchers.Default) { runCatching { qrBitmap(QrShare.encode(login, code)) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share to another phone") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "On the other phone, open TAZ → Settings → Receive a login, scan this code, then type the code below. " +
                        "Tell it in person; don't send it with the picture.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Box(Modifier.size(240.dp).background(Color.White), contentAlignment = Alignment.Center) {
                    val b = bitmap
                    if (b == null) CircularProgressIndicator() else Image(b.asImageBitmap(), contentDescription = "Encrypted QR code of this login", modifier = Modifier.size(240.dp))
                }
                Text(
                    QrShare.formatCode(code),
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

/** Scans a shared-login QR code, asks for its one-time code, shows what's inside and saves it. */
@Composable
fun ReceiveLoginQrDialog(onSave: (SharedLogin) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanned by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<SharedLogin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun accept(text: String?) {
        if (text != null && QrShare.isShareCode(text)) {
            scanned = text
            error = null
        } else {
            error = "That isn't a TAZ login code."
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else error = "Camera access was not allowed. You can pick a screenshot instead."
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { accept(withContext(Dispatchers.Default) { runCatching { QrDecoder.decodeImage(context, uri) }.getOrNull() }) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Receive a login") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val login = opened
                when {
                    login != null -> {
                        Text("Ready to add:", style = MaterialTheme.typography.bodyMedium)
                        Text(login.name, style = MaterialTheme.typography.titleMedium)
                        if (login.username.isNotBlank()) Text(login.username, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (login.url.isNotBlank()) Text(login.url, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    scanned == null -> {
                        Text("On the phone sharing the login, choose \"Share to another phone\". Then scan its QR code here.", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                if (ok) scanning = true else camera.launch(Manifest.permission.CAMERA)
                            }) { Text("Scan") }
                            OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Screenshot") }
                        }
                    }
                    else -> {
                        Text("Type the code shown on the other phone.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.uppercase().take(QrShare.CODE_LENGTH + 1) },
                            label = { Text("Code") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.padding(4.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val login = opened
            when {
                login != null -> TextButton(onClick = { onSave(login) }) { Text("Add to TAZ") }
                scanned != null -> TextButton(enabled = !busy, onClick = {
                    val text = scanned ?: return@TextButton
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.Default) { QrShare.decode(text, code) }
                        busy = false
                        if (result == null) error = "That code doesn't open this login. Check it and try again." else opened = result
                    }
                }) { Text("Open") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (scanning) QrScannerDialog(onResult = { scanning = false; accept(it) }, onDismiss = { scanning = false })
}
