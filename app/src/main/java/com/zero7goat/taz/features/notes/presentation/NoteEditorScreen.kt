@file:OptIn(ExperimentalFoundationApi::class)

package com.zero7goat.taz.features.notes.presentation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.core.ui.replaceAllText
import com.zero7goat.taz.features.notes.data.NoteImageStore
import kotlinx.coroutines.launch

/**
 * Full-screen note editor. Changes are saved when the editor is left and when the app goes
 * to the background. A new note with no content is never stored.
 *
 * The body is plain text with Markdown-style syntax. The formatting bar inserts that syntax,
 * and Preview shows it styled.
 */
@Composable
fun NoteEditorScreen(noteId: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val viewModel: NotesViewModel = viewModel(factory = NotesViewModel.factory(appContext))
    val editor by viewModel.editor.collectAsStateWithLifecycle()

    LaunchedEffect(noteId) { viewModel.openEditor(noteId) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        if (editor.noteId == noteId && editor.isLoaded) {
            EditorContent(editor = editor, viewModel = viewModel, onClose = onClose)
        } else {
            // Still loading: back simply leaves.
            BackHandler {
                viewModel.closeEditor()
                onClose()
            }
        }
    }
}

@Composable
private fun EditorContent(editor: EditorUiState, viewModel: NotesViewModel, onClose: () -> Unit) {
    // Text lives in TextFieldState while typing (synchronous, no cursor jumps) and is copied
    // into the ViewModel draft when the editor is left or the app is backgrounded.
    val titleState = rememberTextFieldState(editor.title)
    val descriptionState = rememberTextFieldState(editor.description)
    val bodyState = rememberTextFieldState(editor.body)
    var confirmDelete by remember { mutableStateOf(false) }
    // Existing notes open for reading; new notes open ready to type.
    var previewing by rememberSaveable { mutableStateOf(!editor.isNewNote) }
    // True once the body has been focused; the formatting bar only acts on the body.
    var bodyActive by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // The system photo picker needs no storage permission. The picked image is copied into
    // private storage and a reference line is inserted at the cursor.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = viewModel.importImage(uri)) {
                    is NoteImageImport.Imported -> bodyState.insertBlockLine(NoteImageStore.markdownFor(result.name))
                    NoteImageImport.TooLarge -> imageError = "Images can be up to 25 MB."
                    NoteImageImport.Unsupported -> imageError = "This image type isn't supported. Use JPEG, PNG, WebP or GIF."
                    NoteImageImport.Failed -> imageError = "The image couldn't be read or stored. Check free storage and try again."
                }
            }
        }
    }

    fun pushText() {
        viewModel.onTitleChange(titleState.text.toString())
        viewModel.onDescriptionChange(descriptionState.text.toString())
        viewModel.onBodyChange(bodyState.text.toString())
    }

    fun finish() {
        pushText()
        viewModel.closeEditor()
        onClose()
    }

    BackHandler { finish() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        pushText()
        viewModel.saveDraft()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
    ) {
        EditorTopBar(
            isPreview = previewing,
            isPinned = editor.isPinned,
            isFavorite = editor.isFavorite,
            onBack = { finish() },
            onTogglePreview = { previewing = !previewing },
            onTogglePinned = viewModel::onPinToggle,
            onToggleFavorite = viewModel::onFavoriteToggle,
            onDelete = {
                if (editor.isNewNote) {
                    viewModel.deleteCurrent()
                    onClose()
                } else {
                    confirmDelete = true
                }
            }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            if (previewing) {
                NotePreview(
                    title = titleState.text.toString(),
                    description = descriptionState.text.toString(),
                    body = bodyState.text.toString(),
                    onBodyChange = { bodyState.replaceAllText(it) }
                )
            } else {
                PlainField(
                    state = titleState,
                    placeholder = "Title",
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) bodyActive = false },
                    lineLimits = TextFieldLineLimits.SingleLine
                )
                Spacer(Modifier.size(8.dp))
                PlainField(
                    state = descriptionState,
                    placeholder = "Description",
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) bodyActive = false }
                )
                ColorRow(selected = editor.colorIndex, onSelect = viewModel::onColorChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.size(16.dp))
                PlainField(
                    state = bodyState,
                    placeholder = "Start writing",
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .heightIn(min = 320.dp)
                        .onFocusChanged { if (it.hasFocus) bodyActive = true }
                )
            }
            Spacer(Modifier.size(32.dp))
        }
        if (!previewing) {
            FormattingBar(
                enabled = bodyActive,
                body = bodyState,
                onInsertImage = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
        }
    }

    imageError?.let { message ->
        AlertDialog(
            onDismissRequest = { imageError = null },
            title = { Text("Image not added") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { imageError = null }) { Text("OK") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Move note to trash?") },
            text = { Text("It will be moved to the trash. You can restore it for 30 days.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteCurrent()
                    onClose()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NotePreview(
    title: String,
    description: String,
    body: String,
    onBodyChange: (String) -> Unit
) {
    Text(
        text = title.ifBlank { "Untitled" },
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    )
    if (description.isNotBlank()) {
        Spacer(Modifier.size(8.dp))
        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge
        )
    }
    Spacer(Modifier.size(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.size(16.dp))
    MarkdownText(source = body, onSourceChange = onBodyChange)
}

@Composable
private fun EditorTopBar(
    isPreview: Boolean,
    isPinned: Boolean,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onTogglePreview: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleFavorite: () -> Unit,
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
        TextButton(onClick = onTogglePreview) { Text(if (isPreview) "Edit" else "Preview") }
        BarButton(
            icon = if (isPinned) TazIcons.PinFilled else TazIcons.Pin,
            description = if (isPinned) "Unpin" else "Pin",
            onClick = onTogglePinned
        )
        BarButton(
            icon = if (isFavorite) TazIcons.StarFilled else TazIcons.Star,
            description = if (isFavorite) "Remove from favorites" else "Add to favorites",
            onClick = onToggleFavorite
        )
        BarButton(TazIcons.Trash, "Delete", onDelete)
    }
}

@Composable
private fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

/**
 * Buttons that insert Markdown-style syntax into the body at the cursor, plus undo and redo of
 * body edits (the text field's own edit history). Scrolls sideways on narrow screens.
 */
@Composable
private fun FormattingBar(enabled: Boolean, body: TextFieldState, onInsertImage: () -> Unit) {
    val history = body.undoState
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FormatIconButton(TazIcons.Undo, "Undo", enabled && history.canUndo) { history.undo() }
            FormatIconButton(TazIcons.Redo, "Redo", enabled && history.canRedo) { history.redo() }
            IconButton(onClick = { body.wrapSelection("**", "**", "bold") }, enabled = enabled) {
                Text("B", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
            IconButton(onClick = { body.wrapSelection("*", "*", "italic") }, enabled = enabled) {
                Text("I", style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic))
            }
            FormatIconButton(TazIcons.Highlight, "Highlight", enabled) {
                body.wrapSelection("==", "==", "highlight")
            }
            FormatIconButton(TazIcons.Quote, "Quote", enabled) {
                body.toggleLinePrefix("> ")
            }
            FormatIconButton(TazIcons.Checkbox, "Checkbox", enabled) {
                body.toggleLinePrefix("- [ ] ", recognised = listOf("- [ ] ", "- [x] ", "- [X] "))
            }
            FormatIconButton(TazIcons.Link, "Link", enabled) { body.insertLink() }
            FormatIconButton(TazIcons.Image, "Insert image", enabled, onInsertImage)
        }
    }
}

@Composable
private fun FormatIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

@Composable
private fun ColorRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NoteColors.forEachIndexed { index, color ->
            val isSelected = index == selected
            val name = NoteColorNames.getOrElse(index) { "Color ${index + 1}" }
            Box(
                modifier = Modifier
                    // One radio option per swatch for screen readers ("Green, selected").
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .semantics { contentDescription = "Note color: $name" }
                    .size(32.dp)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        }
                    )
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(color ?: Color.Transparent)
                    .then(
                        if (color == null) {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        } else {
                            Modifier
                        }
                    )
            )
        }
    }
}

@Composable
private fun PlainField(
    state: TextFieldState,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default
) {
    Box(modifier = modifier.fillMaxWidth()) {
        if (state.text.isEmpty()) {
            Text(
                text = placeholder,
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        BasicTextField(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            lineLimits = lineLimits
        )
    }
}
