package com.zero7goat.taz.features.passwords.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.ui.EmptyState
import com.zero7goat.taz.core.ui.ScreenHeader
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.passwords.domain.HealthEntry
import com.zero7goat.taz.features.passwords.domain.HealthReport
import com.zero7goat.taz.features.passwords.domain.PasswordHealth

/**
 * Shows which logins have weak or reused passwords, or passwords that have not changed for a year.
 * The check runs entirely on this device. Tapping a login opens it so the password can be changed.
 */
@Composable
fun PasswordHealthScreen(
    onClose: () -> Unit,
    onOpenLogin: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: PasswordHealthViewModel =
        viewModel(factory = PasswordHealthViewModel.factory(LocalContext.current.applicationContext))
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Logins may have been edited since the last visit, so check again every time this opens.
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(onBack = onClose)

    Column(modifier = modifier.systemBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(imageVector = TazIcons.ChevronLeft, contentDescription = "Back")
            }
        }
        ScreenHeader("Password health")

        when (val current = state) {
            HealthState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            HealthState.Failed -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = TazIcons.Lock,
                    title = "Couldn't run the check",
                    message = "Your logins could not be read. Nothing was changed.",
                    actionLabel = "Try again",
                    onAction = viewModel::refresh
                )
            }
            is HealthState.Ready -> ReportContent(
                report = current.report,
                onOpenLogin = onOpenLogin,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ReportContent(report: HealthReport, onOpenLogin: (String) -> Unit, modifier: Modifier = Modifier) {
    if (report.checked == 0 && report.unreadable == 0) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = TazIcons.Lock,
                title = "No logins to check",
                message = "Add some logins and TAZ will tell you which passwords are weak, reused or old."
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    text = if (report.isAllGood) "No problems found" else "${report.issueCount} things to look at",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Checked ${report.checked} login${if (report.checked == 1) "" else "s"} on this device. " +
                        "Nothing is sent anywhere.",
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (report.unreadable > 0) {
                    Text(
                        text = "${report.unreadable} login${if (report.unreadable == 1) "" else "s"} " +
                            "could not be decrypted and was not checked.",
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (report.weak.isNotEmpty()) {
            item { SectionHeader("Weak passwords", "Short, common or easy to guess", report.weak.size) }
            entryRows(report.weak, onOpenLogin)
        }
        if (report.reused.isNotEmpty()) {
            item { SectionHeader("Reused passwords", "The same password on more than one login", report.reusedLoginCount) }
            report.reused.forEach { group ->
                item {
                    Text(
                        text = "Shared by ${group.logins.size} logins",
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                entryRows(group.logins, onOpenLogin)
            }
        }
        if (report.old.isNotEmpty()) {
            item {
                SectionHeader(
                    "Not changed in a year",
                    "Last edited over ${PasswordHealth.OLD_AFTER_DAYS} days ago",
                    report.old.size
                )
            }
            entryRows(report.old, onOpenLogin)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.entryRows(
    entries: List<HealthEntry>,
    onOpenLogin: (String) -> Unit
) {
    entries.forEach { entry ->
        item(key = null) { EntryRow(entry, onOpenLogin) }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, count: Int) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 6.dp)) {
        Text(
            text = "${title.uppercase()} ($count)",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun EntryRow(entry: HealthEntry, onOpenLogin: (String) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable { onOpenLogin(entry.id) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.serviceName.ifBlank { "Untitled" },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                style = MaterialTheme.typography.bodyLarge
            )
            TextButton(onClick = { onOpenLogin(entry.id) }) { Text("Open") }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 20.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}
