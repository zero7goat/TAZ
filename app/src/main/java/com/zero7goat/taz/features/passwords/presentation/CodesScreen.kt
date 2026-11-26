package com.zero7goat.taz.features.passwords.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.fields.FieldType
import com.zero7goat.taz.core.security.ClipboardGuard
import com.zero7goat.taz.core.security.KeystoreFieldCipher
import com.zero7goat.taz.core.ui.ScreenHeader
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.core.ui.TotpCodeView
import com.zero7goat.taz.features.passwords.data.CredentialRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class CodeRow(val loginId: String, val name: String, val account: String, val field: CustomField)

/**
 * Every two-factor code in TAZ on one screen, like an authenticator app. Logins with their own
 * lock are left out (open them to see their code). Keys are decrypted only while this is shown.
 */
@Composable
fun CodesScreen(onClose: () -> Unit, onOpenLogin: (String) -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val rows by produceState<List<CodeRow>?>(null) {
        value = withContext(Dispatchers.Default) {
            val repository = CredentialRepository(TazDatabase.getInstance(context).credentialDao(), KeystoreFieldCipher())
            repository.decryptAllForBackup().first
                .filterNot { it.isLocked }
                .flatMap { login ->
                    login.fields.filter { it.type == FieldType.TOTP && it.value.isNotBlank() }
                        .map { CodeRow(login.id, login.serviceName, login.username, it) }
                }
                .sortedBy { it.name.lowercase() }
        }
    }
    Column(modifier.systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(TazIcons.ChevronLeft, contentDescription = "Back") }
        }
        ScreenHeader("Two-factor codes")
        val list = rows
        when {
            list == null -> CircularProgressIndicator(Modifier.padding(32.dp).align(Alignment.CenterHorizontally))
            list.isEmpty() -> Text(
                "No two-factor codes yet. Open a login, add a field and choose \"Two-factor code\", or import them " +
                    "from Google Authenticator in Settings.",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.loginId + it.field.id }) { row ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenLogin(row.loginId) }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        if (row.account.isNotBlank()) {
                            Text(row.account, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Column(Modifier.padding(top = 6.dp)) {
                            TotpCodeView(row.field) { code ->
                                ClipboardGuard.copySensitive(context, code)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
