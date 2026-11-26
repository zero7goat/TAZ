package com.zero7goat.taz.features.passwords.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.ui.FieldSurface
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.passwords.domain.PasswordHistoryEntry
import java.text.DateFormat
import java.util.Date

/**
 * Earlier passwords of a login, collapsed by default. Each stays masked until revealed and can be
 * copied (through the sensitive clipboard by the caller).
 */
@Composable
fun PasswordHistorySection(
    history: List<PasswordHistoryEntry>,
    onCopy: (String) -> Unit,
    onClear: () -> Unit
) {
    if (history.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(setOf<Int>()) }
    Column(Modifier.padding(top = 14.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(TazIcons.Restore, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(10.dp))
            Text(
                "Password history (${history.size})",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge
            )
            Icon(
                if (open) TazIcons.ChevronUp else TazIcons.ChevronDown,
                contentDescription = if (open) "Hide password history" else "Show password history",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(open) {
            Column {
                val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
                history.forEachIndexed { index, entry ->
                    Text(
                        "Replaced ${format.format(Date(entry.replacedAt))}",
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    FieldSurface(innerEndPadding = 2.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val shown = index in revealed
                            Text(
                                if (shown) entry.password else "••••••••",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
                            )
                            IconButton(onClick = { revealed = if (shown) revealed - index else revealed + index }) {
                                Icon(
                                    if (shown) TazIcons.EyeOff else TazIcons.Eye,
                                    contentDescription = if (shown) "Hide old password" else "Show old password",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onCopy(entry.password) }) {
                                Icon(TazIcons.Copy, contentDescription = "Copy old password", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                TextButton(onClick = onClear, modifier = Modifier.padding(top = 6.dp)) { Text("Clear history") }
            }
        }
    }
}
