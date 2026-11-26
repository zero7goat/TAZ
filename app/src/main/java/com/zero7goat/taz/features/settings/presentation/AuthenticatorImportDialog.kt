package com.zero7goat.taz.features.settings.presentation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zero7goat.taz.core.totp.AuthenticatorMigration
import com.zero7goat.taz.core.totp.MigratedAccount
import com.zero7goat.taz.core.totp.QrDecoder
import com.zero7goat.taz.core.totp.QrScannerDialog
import com.zero7goat.taz.core.totp.Totp
import com.zero7goat.taz.core.totp.TotpSpec
import com.zero7goat.taz.core.ui.TazIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Collects two-factor accounts from Google Authenticator's "Transfer accounts" QR codes (one or
 * several, when the export is split) or from ordinary two-factor QR codes, then imports them.
 * The codes are read on the device; the accounts stay in memory until imported.
 */
@Composable
fun AuthenticatorImportDialog(onImport: (List<MigratedAccount>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts = remember { mutableStateListOf<MigratedAccount>() }
    val seenBatches = remember { mutableStateListOf<Int>() }
    var batchSize by remember { mutableStateOf(0) }
    var skipped by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    fun accept(text: String?) {
        if (text == null) {
            message = "No QR code was found."
            return
        }
        val batch = AuthenticatorMigration.parse(text)
        if (batch != null) {
            if (batch.batchIndex in seenBatches) {
                message = "You already scanned this code."
                return
            }
            seenBatches += batch.batchIndex
            batchSize = batch.batchSize
            skipped += batch.skipped
            batch.accounts.forEach { account -> if (accounts.none { it.otpauth == account.otpauth }) accounts += account }
            message = null
            return
        }
        val spec: TotpSpec? = Totp.parse(text)
        if (spec != null && text.trim().startsWith("otpauth://", ignoreCase = true)) {
            accounts += MigratedAccount(spec.issuer, spec.account, text.trim())
            message = null
        } else {
            message = "That isn't a Google Authenticator transfer code or a two-factor QR code."
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else message = "Camera access was not allowed. You can pick a screenshot instead."
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                accept(withContext(Dispatchers.Default) { runCatching { QrDecoder.decodeImage(context, uri) }.getOrNull() })
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from Google Authenticator") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "In Google Authenticator, open the menu and choose Transfer accounts → Export accounts. " +
                        "Then scan the QR code it shows. If it shows several codes, scan each one.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        message = null
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (granted) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
                    }) {
                        Icon(TazIcons.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Scan")
                    }
                    OutlinedButton(onClick = {
                        message = null
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(TazIcons.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Screenshot")
                    }
                }
                if (accounts.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(TazIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "${accounts.size} ${if (accounts.size == 1) "account" else "accounts"} ready" +
                                if (batchSize > 1) " (code ${seenBatches.size} of $batchSize scanned)" else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        accounts.take(8).joinToString("\n") { "• " + listOf(it.issuer, it.account).filter(String::isNotBlank).joinToString(" · ") } +
                            if (accounts.size > 8) "\n…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (skipped > 0) {
                    Text(
                        "$skipped counter-based ${if (skipped == 1) "account" else "accounts"} can't be imported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Each account is added to the matching login in TAZ, or as a new entry. Keep the accounts in " +
                        "Google Authenticator until you've checked the codes in TAZ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(accounts.toList()) }, enabled = accounts.isNotEmpty()) {
                Text(if (accounts.isEmpty()) "Import" else "Import ${accounts.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (scanning) {
        QrScannerDialog(onResult = { scanning = false; accept(it) }, onDismiss = { scanning = false })
    }
}
