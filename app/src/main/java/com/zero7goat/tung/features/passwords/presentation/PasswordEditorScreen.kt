package com.zero7goat.tung.features.passwords.presentation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.tung.core.security.ClipboardGuard
import com.zero7goat.tung.core.security.SecureWindow
import com.zero7goat.tung.core.ui.replaceAllText
import com.zero7goat.tung.core.ui.TungIcons
import com.zero7goat.tung.features.passwords.domain.CredentialDetail
import com.zero7goat.tung.features.passwords.domain.openableUrl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

private const val HIDDEN_PASSWORD = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"

/**
 * Full-screen view and editor for one saved login.
 *
 * An existing login opens read-only (password hidden); a new one opens for editing. Nothing is
 * saved until Save is pressed. While this screen is shown the window is marked secure, so it
 * is excluded from screenshots and the recent-apps preview.
 */
@Composable
fun PasswordEditorScreen(credentialId: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: PasswordsViewModel = viewModel(factory = PasswordsViewModel.factory(appContext))
    val editor by viewModel.editor.collectAsStateWithLifecycle()

    LaunchedEffect(credentialId) { viewModel.openEditor(credentialId) }
    SecureWindowEffect()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        val isThisLogin = editor.credentialId == credentialId
        val draft = viewModel.draft
        when {
            isThisLogin && editor.status == EditorStatus.Ready && draft != null ->
                EditorContent(editor = editor, draft = draft, viewModel = viewModel, onClose = onClose)

            isThisLogin && editor.status == EditorStatus.Unreadable ->
                UnreadableContent(
                    onBack = {
                        viewModel.closeEditor()
                        onClose()
                    },
                    onDelete = {
                        viewModel.deleteCurrent()
                        onClose()
                    }
                )

            else -> {
                // Still loading: back simply leaves.
                BackHandler {
                    viewModel.closeEditor()
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun EditorContent(
    editor: CredentialEditorUi,
    draft: CredentialDraft,
    viewModel: PasswordsViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Not rememberSaveable: the visibility flag and dialogs must never be restored from saved state.
    var reveal by remember { mutableStateOf(false) }
    var showGenerator by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun showMessage(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    // The password goes back to hidden when editing starts or stops, and when the app is backgrounded.
    LaunchedEffect(editor.isEditing) { reveal = false }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { reveal = false }
    LaunchedEffect(editor.saveFailed) {
        if (editor.saveFailed) {
            showMessage("Couldn't save. Check the name and password.")
            viewModel.acknowledgeSaveError()
        }
    }

    fun leave() {
        focusManager.clearFocus()
        viewModel.closeEditor()
        onClose()
    }

    fun requestClose() {
        when {
            editor.isEditing && draft.hasChanges(editor.saved) -> confirmDiscard = true
            editor.isEditing && !editor.isNew -> {
                focusManager.clearFocus()
                viewModel.cancelEditing()
            }
            else -> leave()
        }
    }

    BackHandler { requestClose() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
        ) {
            EditorTopBar(
                isEditing = editor.isEditing,
                isFavorite = editor.isFavorite,
                canSave = draft.canSave && !editor.isSaving,
                onBack = { requestClose() },
                onToggleFavorite = viewModel::toggleEditorFavorite,
                onEdit = viewModel::startEditing,
                onSave = {
                    focusManager.clearFocus()
                    viewModel.save()
                },
                onDelete = { confirmDelete = true }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                val saved = editor.saved
                if (editor.isEditing || saved == null) {
                    EditBody(
                        draft = draft,
                        reveal = reveal,
                        onToggleReveal = { reveal = !reveal },
                        onGenerate = { showGenerator = true }
                    )
                } else {
                    ViewBody(
                        detail = saved,
                        reveal = reveal,
                        onToggleReveal = { reveal = !reveal },
                        onCopyUsername = {
                            ClipboardGuard.copySensitive(context, saved.username)
                            showMessage("Username copied")
                        },
                        onCopyPassword = {
                            ClipboardGuard.copySensitive(context, saved.password)
                            showMessage("Password copied. It will be cleared from the clipboard shortly.")
                        },
                        onOpenWebsite = { url ->
                            try {
                                uriHandler.openUri(url)
                            } catch (e: Exception) {
                                showMessage("No app can open this address")
                            }
                        }
                    )
                }
                Spacer(Modifier.size(32.dp))
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .imePadding()
        )
    }

    if (showGenerator) {
        PasswordGeneratorDialog(
            onDismiss = { showGenerator = false },
            onUse = { generated ->
                draft.password.replaceAllText(generated)
                showGenerator = false
            }
        )
    }

    if (confirmDelete) {
        DeleteDialog(
            onConfirm = {
                confirmDelete = false
                viewModel.deleteCurrent()
                onClose()
            },
            onDismiss = { confirmDelete = false }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your unsaved changes will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    if (editor.isNew) leave() else {
                        focusManager.clearFocus()
                        viewModel.cancelEditing()
                    }
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            }
        )
    }
}

@Composable
private fun DeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete login?") },
        text = { Text("This login will be permanently deleted. This can't be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditorTopBar(
    isEditing: Boolean,
    isFavorite: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BarButton(TungIcons.ChevronLeft, "Back", onBack)
        Spacer(Modifier.weight(1f))
        BarButton(
            icon = if (isFavorite) TungIcons.StarFilled else TungIcons.Star,
            description = if (isFavorite) "Remove from favorites" else "Add to favorites",
            onClick = onToggleFavorite
        )
        if (isEditing) {
            TextButton(onClick = onSave, enabled = canSave) { Text("Save") }
        } else {
            TextButton(onClick = onEdit) { Text("Edit") }
            BarButton(TungIcons.Trash, "Delete", onDelete)
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

// ---------- read-only view ----------

@Composable
private fun ViewBody(
    detail: CredentialDetail,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    onOpenWebsite: (String) -> Unit
) {
    Text(
        text = detail.serviceName,
        modifier = Modifier.padding(top = 8.dp),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    )

    ViewField(label = "Username", value = detail.username.ifEmpty { "Not set" }) {
        if (detail.username.isNotEmpty()) TextButton(onClick = onCopyUsername) { Text("Copy") }
    }
    ViewField(
        label = "Password",
        value = if (reveal) detail.password else HIDDEN_PASSWORD,
        monospace = true
    ) {
        TextButton(onClick = onToggleReveal) { Text(if (reveal) "Hide" else "Show") }
        TextButton(onClick = onCopyPassword) { Text("Copy") }
    }
    if (detail.websiteUrl.isNotBlank()) {
        val openable = openableUrl(detail.websiteUrl)
        ViewField(label = "Website", value = detail.websiteUrl) {
            if (openable != null) TextButton(onClick = { onOpenWebsite(openable) }) { Text("Open") }
        }
    }
    if (detail.notes.isNotBlank()) {
        ViewField(label = "Notes", value = detail.notes)
    }

    Text(
        text = "Created ${formatTimestamp(detail.createdAt)}\nModified ${formatTimestamp(detail.updatedAt)}",
        modifier = Modifier.padding(top = 24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun ViewField(
    label: String,
    value: String,
    monospace: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
    FieldLabel(label)
    FieldContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
                )
            )
            actions()
        }
    }
}

// ---------- editing ----------

@Composable
private fun EditBody(
    draft: CredentialDraft,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    onGenerate: () -> Unit
) {
    FieldLabel("Service or website *")
    FieldContainer {
        TextInput(
            state = draft.serviceName,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
        )
    }

    FieldLabel("Username or email")
    FieldContainer {
        TextInput(
            state = draft.username,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Email
            )
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel("Password *")
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onGenerate) { Text("Generate") }
        TextButton(onClick = onToggleReveal) { Text(if (reveal) "Hide" else "Show") }
    }
    FieldContainer {
        BasicSecureTextField(
            state = draft.password,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            textObfuscationMode = if (reveal) TextObfuscationMode.Visible else TextObfuscationMode.Hidden
        )
    }

    FieldLabel("Website address")
    FieldContainer {
        TextInput(
            state = draft.websiteUrl,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                keyboardType = KeyboardType.Uri
            )
        )
    }
    val url = draft.websiteUrl.text.toString()
    if (url.isNotBlank() && openableUrl(url) == null) {
        Text(
            text = "This isn't a web address that can be opened. It will still be saved.",
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }

    FieldLabel("Notes")
    FieldContainer {
        TextInput(
            state = draft.notes,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            singleLine = false,
            modifier = Modifier.heightIn(min = 96.dp)
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun FieldContainer(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
    }
}

@Composable
private fun TextInput(
    state: TextFieldState,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true
) {
    BasicTextField(
        state = state,
        modifier = modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        keyboardOptions = keyboardOptions,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default
    )
}

// ---------- unreadable entry ----------

@Composable
private fun UnreadableContent(onBack: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            BarButton(TungIcons.ChevronLeft, "Back", onBack)
        }
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "This login can't be read",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "It couldn't be decrypted on this device. This can happen if the device's " +
                    "encryption key was lost or reset. You can delete the entry.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = { confirmDelete = true }) { Text("Delete entry") }
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

// ---------- helpers ----------

/** Marks the window secure while this screen is shown (no screenshots, blank recents preview). */
@Composable
private fun SecureWindowEffect() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        if (window != null) SecureWindow.acquire(window)
        onDispose { if (window != null) SecureWindow.release(window) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatTimestamp(millis: Long): String =
    timestampFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
