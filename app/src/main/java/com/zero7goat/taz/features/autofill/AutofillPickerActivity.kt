package com.zero7goat.taz.features.autofill

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.zero7goat.taz.core.AppContainer
import com.zero7goat.taz.core.security.AppLockManager
import com.zero7goat.taz.core.security.ClipboardGuard
import com.zero7goat.taz.core.settings.AppSettings
import com.zero7goat.taz.core.ui.AppFonts
import com.zero7goat.taz.core.ui.AppLockGate
import com.zero7goat.taz.core.ui.ItemLockGate
import com.zero7goat.taz.core.ui.itemLockKey
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.ui.theme.TAZTheme
import kotlinx.coroutines.launch

/**
 * Opened by Android when the user taps a TAZ suggestion in another app. Shows TAZ's lock screen
 * if TAZ is locked, then the logins that fit (with a search over all of them), and hands the
 * chosen login back to Android to fill in. Screenshots are always blocked here.
 */
class AutofillPickerActivity : FragmentActivity() {

    private lateinit var lock: AppLockManager
    private val vault by lazy { AutofillVault(applicationContext) }

    private var usernameIds: List<AutofillId> = emptyList()
    private var passwordIds: List<AutofillId> = emptyList()
    private var otpIds: List<AutofillId> = emptyList()
    private var webDomain: String? = null
    private var targetPackage: String = ""
    private var mode: PickMode = PickMode.LOGIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lock = AppContainer.lockManager(applicationContext)
        val settingsRepository = AppContainer.settings(applicationContext)
        usernameIds = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_USERNAME_IDS, AutofillId::class.java).orEmpty()
        passwordIds = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_PASSWORD_IDS, AutofillId::class.java).orEmpty()
        otpIds = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_OTP_IDS, AutofillId::class.java).orEmpty()
        webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
        mode = PickMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: PickMode.LOGIN
        val webDomain = webDomain
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val packageName = targetPackage
        val preselected = intent.getStringExtra(EXTRA_CREDENTIAL_ID)
        setResult(Activity.RESULT_CANCELED)
        enableEdgeToEdge()

        setContent {
            val settings by produceState<AppSettings?>(null) { settingsRepository.settings.collect { value = it } }
            val loaded = settings ?: return@setContent
            TAZTheme(themeMode = loaded.themeMode, fontFamily = AppFonts.familyFor(loaded.fontKey)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    AppLockGate(lock = lock, biometricEnabled = loaded.biometricEnabled, onLocked = {}) {
                        PickerScreen(
                            where = webDomain ?: AutofillMatcher.nameForPackage(packageName),
                            webDomain = webDomain,
                            packageName = packageName,
                            preselected = preselected,
                            onPick = ::finishWith,
                            onClose = ::finish
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PickerScreen(
        where: String,
        webDomain: String?,
        packageName: String,
        preselected: String?,
        onPick: suspend (String) -> Unit,
        onClose: () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(preselected != null) }
        // Every login, and the ones that fit this app or site.
        val loaded by produceState<Pair<List<AutofillEntry>, List<AutofillEntry>>?>(null) {
            val all = vault.all()
            value = all to vault.matches(all, webDomain, packageName)
        }
        val entries = loaded?.first

        // A suggestion for one particular login was tapped: fill it straight away (TAZ is unlocked here).
        // A login with its own lock asks for the passcode again before it is filled.
        var gateFor by remember { mutableStateOf<AutofillEntry?>(null) }
        fun choose(entry: AutofillEntry) {
            if (entry.isLocked) {
                gateFor = entry
            } else {
                busy = true
                scope.launch { onPick(entry.id) }
            }
        }
        LaunchedEffect(preselected, entries) {
            val all = entries ?: return@LaunchedEffect
            if (preselected == null) return@LaunchedEffect
            val entry = all.firstOrNull { it.id == preselected }
            busy = false
            if (entry == null) onClose() else choose(entry)
        }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fill with TAZ", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text(where, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose) { Icon(TazIcons.Close, contentDescription = "Close") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text("Search logins") },
                leadingIcon = { Icon(TazIcons.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            val gate = gateFor
            val all = entries
            if (gate != null) {
                ItemLockGate(locked = true, itemKey = itemLockKey("LOGIN", gate.id), title = gate.serviceName) {
                    LaunchedEffect(gate.id) { onPick(gate.id) }
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else if (all == null || busy) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (all.isEmpty()) {
                Text(
                    "No logins in TAZ yet. Add one in TAZ, or let TAZ save this one after you sign in.",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val q = query.trim().lowercase()
                val shown = if (q.isEmpty()) {
                    val suggested = loaded?.second.orEmpty()
                    val ids = suggested.map { it.id }.toSet()
                    listOf("Suggested" to suggested, "All logins" to all.filterNot { it.id in ids }.sortedBy { it.serviceName.lowercase() })
                } else {
                    listOf(
                        "Results" to all.filter {
                            it.serviceName.lowercase().contains(q) || it.domain.contains(q) || it.username.lowercase().contains(q)
                        }
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    shown.forEach { (title, list) ->
                        if (list.isEmpty()) return@forEach
                        item(key = "h-$title") {
                            Text(
                                title.uppercase(),
                                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        items(list, key = { "$title-${it.id}" }) { entry ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        choose(entry)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Icon(TazIcons.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f)) {
                                    Text(entry.serviceName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sub = entry.username.ifEmpty { entry.domain }
                                    if (sub.isNotEmpty()) {
                                        Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    item { Spacer(Modifier.size(24.dp)) }
                }
            }
        }
    }

    /** Decrypts the chosen login and returns it (or just its code) to Android, which fills the form. */
    private suspend fun finishWith(id: String) {
        val detail = vault.detail(id)
        if (detail == null) {
            Toast.makeText(this, "This login couldn't be opened", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (mode == PickMode.CODE) {
            val dataset = AutofillDatasets.codeOnly(this, otpIds, detail)
            if (dataset == null) {
                Toast.makeText(this, "This login has no two-factor code", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
            finish()
            return
        }
        val dataset = AutofillDatasets.filled(this, usernameIds, passwordIds, otpIds, detail)
        RecentFills.record(webDomain, targetPackage, detail.id)
        // Picked for an app: suggest this login there first next time.
        if (webDomain.isNullOrBlank()) vault.remember(targetPackage, detail.id)
        // No code box on this screen: the next step of many sign-ins is the code, so have it ready.
        if (otpIds.isEmpty()) {
            currentCode(detail)?.let { code ->
                ClipboardGuard.copySensitive(this, code)
                Toast.makeText(this, "Two-factor code copied", Toast.LENGTH_SHORT).show()
            }
        }
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
        finish()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lock.onUserInteraction()
    }

    override fun onStart() {
        super.onStart()
        // Same auto-lock rule as opening TAZ itself.
        lock.onAppStarted()
    }

    override fun onStop() {
        if (!isChangingConfigurations) lock.onAppStopped()
        super.onStop()
        // Never left lying around behind other apps.
        if (!isChangingConfigurations) finish()
    }

    companion object {
        const val EXTRA_USERNAME_IDS = "com.zero7goat.taz.autofill.USERNAME_IDS"
        const val EXTRA_PASSWORD_IDS = "com.zero7goat.taz.autofill.PASSWORD_IDS"
        const val EXTRA_WEB_DOMAIN = "com.zero7goat.taz.autofill.WEB_DOMAIN"
        const val EXTRA_PACKAGE = "com.zero7goat.taz.autofill.PACKAGE"
        const val EXTRA_CREDENTIAL_ID = "com.zero7goat.taz.autofill.CREDENTIAL_ID"
        const val EXTRA_OTP_IDS = "com.zero7goat.taz.autofill.OTP_IDS"
        const val EXTRA_MODE = "com.zero7goat.taz.autofill.MODE"
    }
}
