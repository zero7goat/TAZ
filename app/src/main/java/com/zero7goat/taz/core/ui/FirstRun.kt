package com.zero7goat.taz.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The versionCode whose "What's new" this build shows. */
const val WHATS_NEW_VERSION = 4

private class Page(val icon: ImageVector, val title: String, val text: String)

/** First start on a new install: what TAZ is, in three short pages. */
@Composable
fun WelcomeScreen(onDone: (setUpLock: Boolean) -> Unit) {
    val pages = remember {
        listOf(
            Page(TazIcons.Shield, "Welcome to TAZ", "One private place for your notes, passwords, two-factor codes and files."),
            Page(TazIcons.Lock, "Everything stays on this phone", "TAZ works fully offline. No account, no cloud, no tracking: it has no internet access at all. Your data is encrypted with a key that never leaves the phone."),
            Page(TazIcons.Backup, "Keep a backup", "Because nothing is in the cloud, a lost phone means lost data unless you keep a backup. Turn on automatic encrypted backups in Settings, and print the emergency sheet.")
        )
    }
    var index by remember { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        AnimatedContent(targetState = index, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "welcome") { i ->
            val page = pages[i]
            Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (i == 0) TazLogo(height = 96.dp, contentDescription = "TAZ") else Icon(page.icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text(page.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(page.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            pages.indices.forEach { i ->
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(if (i == index) 22.dp else 8.dp, 8.dp)
                        .background(
                            if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(4.dp)
                        )
                )
            }
        }
        if (index < pages.lastIndex) {
            Button(onClick = { index++ }, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Next") }
            TextButton(onClick = { onDone(false) }) { Text("Skip") }
        } else {
            Button(onClick = { onDone(true) }, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Set up app lock") }
            TextButton(onClick = { onDone(false) }) { Text("Maybe later") }
        }
    }
}

/** Shown once after updating to 2.0. */
@Composable
fun WhatsNewScreen(onDone: () -> Unit) {
    val items = remember {
        listOf(
            Page(TazIcons.Autofill, "Autofill", "Fill logins in apps and browsers, save new ones, and get the two-factor code filled in too. Settings → Autofill."),
            Page(TazIcons.Timer, "Two-factor codes", "Add a site's QR code to a login, or import everything from Google Authenticator. All codes: Settings → Two-factor codes."),
            Page(TazIcons.Import, "Move in easily", "Import from Chrome, Firefox, Bitwarden, KeePass, 1Password, Proton Pass and more."),
            Page(TazIcons.Lock, "More protection", "Files are now encrypted, items can have their own lock, and there's a duress passcode, lock on screen off and a disguised icon."),
            Page(TazIcons.Calendar, "Automatic backups and reminders", "Encrypted backups on a schedule, reminders for notes, expiring cards and old passwords."),
            Page(TazIcons.Camera, "Capture into the vault", "Take photos and videos, scan documents to PDF and record voice notes without them ever touching your gallery.")
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("What's new in TAZ 2.0", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(vertical = 16.dp))
        Column(
            Modifier
                .weight(1f)
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            items.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.size(16.dp))
                    Column {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(item.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(52.dp).padding(top = 4.dp), shape = RoundedCornerShape(14.dp)) { Text("Continue") }
    }
}
