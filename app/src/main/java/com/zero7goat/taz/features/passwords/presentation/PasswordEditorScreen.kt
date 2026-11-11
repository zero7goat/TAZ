package com.zero7goat.taz.features.passwords.presentation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import com.zero7goat.taz.core.fields.CustomField
import com.zero7goat.taz.core.tags.TagTarget
import com.zero7goat.taz.core.ui.CustomFieldsEditor
import com.zero7goat.taz.core.ui.CustomFieldsView
import com.zero7goat.taz.core.ui.TagPickerSheet
import com.zero7goat.taz.core.ui.TagsSection
import com.zero7goat.taz.features.passwords.domain.Categories
import com.zero7goat.taz.features.passwords.domain.LoginCategory
import com.zero7goat.taz.features.tags.TagsViewModel
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.security.ClipboardGuard
import com.zero7goat.taz.core.security.SecureWindow
import com.zero7goat.taz.core.ui.replaceAllText
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.core.ui.AnimatedIcon
import com.zero7goat.taz.features.passwords.domain.CredentialDetail
import com.zero7goat.taz.features.passwords.domain.openableUrl
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

    val tagsViewModel: TagsViewModel = viewModel(factory = TagsViewModel.factory(context.applicationContext))
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val allTags by tagsViewModel.tags.collectAsStateWithLifecycle()
    val loginId = editor.credentialId.orEmpty()
    val itemTags by remember(loginId) { tagsViewModel.itemTags(TagTarget.LOGIN, loginId) }
        .collectAsStateWithLifecycle(emptyList())
    val category = Categories.resolve(draft.category, categories)

    // Not rememberSaveable: visibility flags and dialogs must never be restored from saved state.
    var reveal by remember { mutableStateOf(false) }
    var revealedFields by remember { mutableStateOf(emptySet<String>()) }
    // What the generator fills: PASSWORD_TARGET for the main password, otherwise a field id.
    var generatorTarget by remember { mutableStateOf<String?>(null) }
    var pickingCategory by remember { mutableStateOf(false) }
    var pickingTags by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun showMessage(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    // The password goes back to hidden when editing starts or stops, and when the app is backgrounded.
    LaunchedEffect(editor.isEditing) {
        reveal = false
        revealedFields = emptySet()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        reveal = false
        revealedFields = emptySet()
    }
    LaunchedEffect(editor.saveFailed) {
        if (editor.saveFailed) {
            showMessage("Couldn't save. Check the name and password.")
            viewModel.acknowledgeSaveError()
        }
    }

    fun leave() {
        focusManager.clearFocus()
        // A new login that is left without saving keeps no tags behind.
        if (editor.isNew) tagsViewModel.setItemTags(TagTarget.LOGIN, loginId, emptySet())
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
                val openUrl: (String) -> Unit = { url ->
                    val openable = openableUrl(url)
                    try {
                        if (openable == null) throw IllegalArgumentException() else uriHandler.openUri(openable)
                    } catch (e: Exception) {
                        showMessage("No app can open this address")
                    }
                }
                AnimatedContent(
                    targetState = editor.isEditing || saved == null,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) },
                    label = "login-mode"
                ) { editing ->
                    Column {
                        if (editing || saved == null) {
                            EditBody(
                                draft = draft,
                                category = category,
                                reveal = reveal,
                                onToggleReveal = { reveal = !reveal },
                                onGenerate = { generatorTarget = PASSWORD_TARGET },
                                onPickCategory = { pickingCategory = true },
                                revealedFields = revealedFields,
                                onToggleFieldReveal = { id ->
                                    revealedFields = if (id in revealedFields) revealedFields - id else revealedFields + id
                                },
                                onGenerateField = { id -> generatorTarget = id }
                            )
                        } else {
                            ViewBody(
                                detail = saved,
                                category = Categories.resolve(saved.category, categories),
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
                                onOpenWebsite = openUrl,
                                revealedFields = revealedFields,
                                onToggleFieldReveal = { id ->
                                    revealedFields = if (id in revealedFields) revealedFields - id else revealedFields + id
                                },
                                onCopyField = { field ->
                                    ClipboardGuard.copySensitive(context, field.value)
                                    showMessage(
                                        if (field.isHidden) "${field.label.ifBlank { "Value" }} copied. It will be cleared shortly."
                                        else "${field.label.ifBlank { "Value" }} copied"
                                    )
                                }
                            )
                        }
                        TagsSection(
                            tags = itemTags,
                            editable = true,
                            onEdit = { pickingTags = true },
                            onRemove = { tag -> tagsViewModel.removeTag(TagTarget.LOGIN, loginId, tag.id) },
                            modifier = Modifier.padding(top = 20.dp)
                        )
                        if (saved != null && !(editing)) {
                            Text(
                                text = "Created ${formatTimestamp(saved.createdAt)}\nModified ${formatTimestamp(saved.updatedAt)}",
                                modifier = Modifier.padding(top = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
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

    generatorTarget?.let { target ->
        PasswordGeneratorDialog(
            onDismiss = { generatorTarget = null },
            onUse = { generated ->
                if (target == PASSWORD_TARGET) {
                    draft.password.replaceAllText(generated)
                } else {
                    draft.fields = draft.fields.map { if (it.id == target) it.copy(value = generated) else it }
                }
                generatorTarget = null
            }
        )
    }

    if (pickingCategory) {
        CategoryPickerSheet(
            categories = categories,
            selectedKey = draft.category,
            onSelect = { chosen ->
                draft.applyCategory(chosen)
                pickingCategory = false
            },
            onDismiss = { pickingCategory = false }
        )
    }

    if (pickingTags) {
        TagPickerSheet(
            allTags = allTags,
            initiallySelected = itemTags.mapTo(HashSet()) { it.id },
            onCreate = { tagsViewModel.createOrGet(it) },
            onDone = { ids ->
                tagsViewModel.setItemTags(TagTarget.LOGIN, loginId, ids)
                pickingTags = false
            },
            onDismiss = { pickingTags = false }
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
        title = { Text("Move login to trash?") },
        text = { Text("It will be moved to the trash. You can restore it for 30 days.") },
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
        BarButton(TazIcons.ChevronLeft, "Back", onBack)
        Spacer(Modifier.weight(1f))
        BarButton(
            icon = if (isFavorite) TazIcons.StarFilled else TazIcons.Star,
            description = if (isFavorite) "Remove from favorites" else "Add to favorites",
            onClick = onToggleFavorite
        )
        if (isEditing) {
            TextButton(onClick = onSave, enabled = canSave) { Text("Save") }
        } else {
            TextButton(onClick = onEdit) { Text("Edit") }
            BarButton(TazIcons.Trash, "Delete", onDelete)
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        AnimatedIcon(icon = icon, contentDescription = description)
    }
}

// ---------- read-only view ----------

private const val PASSWORD_TARGET = "\u0000password"

@Composable
private fun ViewBody(
    detail: CredentialDetail,
    category: LoginCategory,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    onOpenWebsite: (String) -> Unit,
    revealedFields: Set<String>,
    onToggleFieldReveal: (String) -> Unit,
    onCopyField: (CustomField) -> Unit
) {
    Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryBadge(category.icon, size = 44.dp)
        Spacer(Modifier.size(14.dp))
        Column {
            Text(
                text = detail.serviceName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = category.name,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    // The login fields show for the Login category, and for any entry where they hold a value.
    if (category.usesLoginFields || detail.username.isNotEmpty()) {
        ViewField(label = "Username", value = detail.username.ifEmpty { "Not set" }) {
            if (detail.username.isNotEmpty()) {
                IconButton(onClick = onCopyUsername) {
                    Icon(TazIcons.Copy, contentDescription = "Copy username", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (category.usesLoginFields || detail.password.isNotEmpty()) {
        ViewField(
            label = "Password",
            value = if (reveal) detail.password else HIDDEN_PASSWORD,
            monospace = true
        ) {
            RevealButton(reveal = reveal, onToggle = onToggleReveal)
            IconButton(onClick = onCopyPassword) {
                Icon(TazIcons.Copy, contentDescription = "Copy password", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (detail.websiteUrl.isNotBlank()) {
        val openable = openableUrl(detail.websiteUrl)
        ViewField(label = "Website", value = detail.websiteUrl) {
            if (openable != null) {
                IconButton(onClick = { onOpenWebsite(openable) }) {
                    Icon(TazIcons.OpenExternal, contentDescription = "Open website", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (detail.fields.isNotEmpty()) {
        CustomFieldsView(
            fields = detail.fields,
            revealed = revealedFields,
            onToggleReveal = onToggleFieldReveal,
            onCopy = onCopyField,
            onOpenUrl = onOpenWebsite
        )
    }
    if (detail.notes.isNotBlank()) {
        ViewField(label = "Notes", value = detail.notes)
    }
}

@Composable
private fun ViewField(
    label: String,
    value: String,
    monospace: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}
) {
    FieldLabel(label)
    FieldContainer(innerEndPadding = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp, horizontal = 0.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
                )
            )
            actions()
            Spacer(Modifier.size(12.dp))
        }
    }
}

// ---------- editing ----------

@Composable
private fun EditBody(
    draft: CredentialDraft,
    category: LoginCategory,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    onGenerate: () -> Unit,
    onPickCategory: () -> Unit,
    revealedFields: Set<String>,
    onToggleFieldReveal: (String) -> Unit,
    onGenerateField: (String) -> Unit
) {
    CategoryButton(category = category, onClick = onPickCategory, modifier = Modifier.padding(top = 8.dp))

    FieldLabel(if (category.usesLoginFields) "Service or website *" else "Name *")
    FieldContainer {
        TextInput(
            state = draft.serviceName,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
        )
    }

    // Login fields: always for Login, and kept visible on other categories while they hold a value.
    val showUsername = category.usesLoginFields || draft.username.text.isNotEmpty()
    val showPassword = category.usesLoginFields || draft.password.text.isNotEmpty()
    val showWebsite = category.usesLoginFields || draft.websiteUrl.text.isNotEmpty()

    if (showUsername) {
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
    }

    if (showPassword) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(if (category.usesLoginFields) "Password *" else "Password")
            Spacer(Modifier.weight(1f))
            // The generator sits right next to the field it fills.
            TextButton(onClick = onGenerate) {
                Icon(TazIcons.Generate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Generate")
            }
        }
        FieldContainer(innerEndPadding = 2.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Toggling only changes how the text is drawn: the same TextFieldState keeps the
                // password, the cursor position and any selection exactly as they were.
                BasicSecureTextField(
                    state = draft.password,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    textObfuscationMode = if (reveal) TextObfuscationMode.Visible else TextObfuscationMode.Hidden
                )
                RevealButton(reveal = reveal, onToggle = onToggleReveal)
            }
        }
    }

    if (showWebsite) {
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
    }

    CustomFieldsEditor(
        fields = draft.fields,
        onChange = { draft.fields = it },
        revealed = revealedFields,
        onToggleReveal = onToggleFieldReveal,
        onGenerate = onGenerateField
    )

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
private fun FieldContainer(innerEndPadding: Dp = 14.dp, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(start = 14.dp, end = innerEndPadding, top = 10.dp, bottom = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) { content() }
    }
}

/** Eye icon that shows or hides a password. 48dp touch target, announced as an action. */
@Composable
internal fun RevealButton(reveal: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (reveal) TazIcons.EyeOff else TazIcons.Eye,
            contentDescription = if (reveal) "Hide password" else "Show password",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            BarButton(TazIcons.ChevronLeft, "Back", onBack)
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
