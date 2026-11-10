package com.zero7goat.taz.core.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zero7goat.taz.core.AppShortcuts
import com.zero7goat.taz.core.ui.TazIcons
import com.zero7goat.taz.features.passwords.presentation.PasswordHealthScreen
import com.zero7goat.taz.features.trash.presentation.TrashScreen
import java.util.UUID
import com.zero7goat.taz.features.files.presentation.FileDetailScreen
import com.zero7goat.taz.features.files.presentation.FilesScreen
import com.zero7goat.taz.features.notes.presentation.NoteEditorScreen
import com.zero7goat.taz.features.notes.presentation.NotesScreen
import com.zero7goat.taz.features.passwords.presentation.PasswordEditorScreen
import com.zero7goat.taz.features.passwords.presentation.PasswordsScreen
import com.zero7goat.taz.features.settings.presentation.SettingsScreen
import kotlinx.coroutines.launch

private data class TopLevelDestination(
    val route: Route,
    val label: String,
    val icon: ImageVector
)

private val destinations = listOf(
    TopLevelDestination(Route.Notes, "Notes", TazIcons.Notes),
    TopLevelDestination(Route.Passwords, "Passwords", TazIcons.Lock),
    TopLevelDestination(Route.Files, "Files", TazIcons.Folder),
    TopLevelDestination(Route.Settings, "Settings", TazIcons.Sliders)
)

/** Where the user is: the tabbed shell, or a full-screen editor or viewer on top of it. */
private sealed interface Screen {
    data object Shell : Screen
    data class NoteEditor(val id: String) : Screen
    data class PasswordEditor(val id: String) : Screen
    data class FileViewer(val id: String) : Screen
    data object Trash : Screen
    data object PasswordHealth : Screen
}

private val ContentMaxWidth = 720.dp

/**
 * The app shell: bottom navigation between the four top-level sections, with notes, logins and
 * files opening full screen on top. The selected section is saved across rotation, and only ids
 * (never credential data) are saved for the open item.
 */
@Composable
fun AppNavigation(modifier: Modifier = Modifier) {
    var currentPath by rememberSaveable { mutableStateOf(Route.Notes.path) }
    val currentRoute = Route.fromPath(currentPath)

    // The note being edited. While set, the editor covers the whole shell (bottom bar hidden).
    var editingNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    // The login being viewed or edited (only its id is saved, never any credential data).
    var editingCredentialId by rememberSaveable { mutableStateOf<String?>(null) }
    // The stored file being viewed (only its id is saved).
    var viewingFileId by rememberSaveable { mutableStateOf<String?>(null) }
    // Full-screen tools opened from Settings and Passwords.
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var showPasswordHealth by rememberSaveable { mutableStateOf(false) }
    val shellStateHolder = rememberSaveableStateHolder()

    // The home-screen "New note" shortcut. This composable only exists once TAZ is unlocked, so the
    // new note opens after the passcode (or biometric) check, never before it.
    val shortcutRequestedAt by AppShortcuts.newNoteRequestedAt.collectAsState()
    LaunchedEffect(shortcutRequestedAt) {
        if (shortcutRequestedAt != 0L) {
            if (AppShortcuts.isFresh(shortcutRequestedAt, System.currentTimeMillis())) {
                currentPath = Route.Notes.path
                editingCredentialId = null
                viewingFileId = null
                showTrash = false
                showPasswordHealth = false
                editingNoteId = UUID.randomUUID().toString()
            }
            AppShortcuts.clearNewNote()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    val openNoteId = editingNoteId
    val openCredentialId = editingCredentialId
    val openFileId = viewingFileId
    val screen: Screen = when {
        openNoteId != null -> Screen.NoteEditor(openNoteId)
        openCredentialId != null -> Screen.PasswordEditor(openCredentialId)
        openFileId != null -> Screen.FileViewer(openFileId)
        showPasswordHealth -> Screen.PasswordHealth
        showTrash -> Screen.Trash
        else -> Screen.Shell
    }

    AnimatedContent(
        targetState = screen,
        modifier = modifier,
        transitionSpec = {
            if (targetState is Screen.Shell) {
                // Back to the lists: a plain, quick fade.
                fadeIn(tween(160)) togetherWith fadeOut(tween(120))
            } else {
                // Opening an item: fade in with a small slide so it reads as a step forward.
                (fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 14 }) togetherWith fadeOut(tween(120))
            }
        },
        label = "screen"
    ) { current ->
        when (current) {
            is Screen.NoteEditor -> ContentFrame {
                NoteEditorScreen(
                    noteId = current.id,
                    onClose = { editingNoteId = null },
                    modifier = Modifier.fillMaxSize()
                )
            }
            is Screen.PasswordEditor -> ContentFrame {
                PasswordEditorScreen(
                    credentialId = current.id,
                    onClose = { editingCredentialId = null },
                    modifier = Modifier.fillMaxSize()
                )
            }
            is Screen.FileViewer -> ContentFrame {
                FileDetailScreen(
                    fileId = current.id,
                    onClose = { viewingFileId = null },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Screen.PasswordHealth -> ContentFrame {
                PasswordHealthScreen(
                    onClose = { showPasswordHealth = false },
                    onOpenLogin = { editingCredentialId = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Screen.Trash -> ContentFrame {
                TrashScreen(onClose = { showTrash = false }, modifier = Modifier.fillMaxSize())
            }
            Screen.Shell -> {
                // Keeps list scroll position and search text while an editor is open.
                shellStateHolder.SaveableStateProvider("shell") {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            TazBottomBar(
                                current = currentRoute,
                                onSelect = { currentPath = it.path }
                            )
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            ContentFrame {
                                Crossfade(targetState = currentRoute, animationSpec = tween(150), label = "tab") { route ->
                                    val screenModifier = Modifier.fillMaxSize()
                                    when (route) {
                                        Route.Notes -> NotesScreen(
                                            onOpenNote = { editingNoteId = it },
                                            modifier = screenModifier
                                        )
                                        Route.Passwords -> PasswordsScreen(
                                            onOpenCredential = { editingCredentialId = it },
                                            onOpenHealth = { showPasswordHealth = true },
                                            modifier = screenModifier
                                        )
                                        Route.Files -> FilesScreen(
                                            onOpenFile = { viewingFileId = it },
                                            onMessage = showMessage,
                                            modifier = screenModifier
                                        )
                                        Route.Settings -> SettingsScreen(
                                            onMessage = showMessage,
                                            onOpenTrash = { showTrash = true },
                                            onOpenPasswordHealth = { showPasswordHealth = true },
                                            modifier = screenModifier
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Keeps content to a comfortable reading width on tablets and in landscape, while the background
 * still fills the whole screen so there are no bands.
 */
@Composable
private fun ContentFrame(content: @Composable () -> Unit) {
    // A Surface (not a plain background) so every screen inherits the theme's content colour.
    // Without it, icons drawn outside a Surface (back arrows, toolbar actions) fall back to
    // black and disappear in the dark themes.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize()) { content() }
        }
    }
}

@Composable
private fun TazBottomBar(current: Route, onSelect: (Route) -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            destinations.forEach { destination ->
                NavigationBarItem(
                    selected = destination.route == current,
                    onClick = { onSelect(destination.route) },
                    icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                    label = { Text(destination.label) },
                    alwaysShowLabel = true
                )
            }
        }
    }
}
