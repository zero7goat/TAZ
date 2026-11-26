package com.zero7goat.taz.core.navigation

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zero7goat.taz.core.ui.WHATS_NEW_VERSION
import com.zero7goat.taz.core.ui.WhatsNewScreen
import com.zero7goat.taz.core.ui.WelcomeScreen
import com.zero7goat.taz.core.database.TazDatabase
import com.zero7goat.taz.core.AppContainer
import com.zero7goat.taz.core.settings.FeaturePrefs
import com.zero7goat.taz.features.passwords.presentation.CodesScreen
import androidx.compose.runtime.key
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zero7goat.taz.core.database.FolderModule
import com.zero7goat.taz.core.ui.LocalOpenSearch
import com.zero7goat.taz.features.files.presentation.FilesViewModel
import com.zero7goat.taz.features.notes.presentation.NotesViewModel
import com.zero7goat.taz.features.passwords.presentation.PasswordsViewModel
import com.zero7goat.taz.features.search.presentation.SearchScreen
import com.zero7goat.taz.features.passwords.presentation.CategoriesScreen
import com.zero7goat.taz.features.tags.TagsScreen
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
    data object Tags : Screen
    data object Categories : Screen
    data object Search : Screen
    data object Codes : Screen
}

private val ContentMaxWidth = 720.dp
private const val TWO_PANE_MIN_WIDTH_DP = 840
private val LIST_PANE_WIDTH = 400.dp

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
    var showTags by rememberSaveable { mutableStateOf(false) }
    var showCategories by rememberSaveable { mutableStateOf(false) }
    // Vault-wide search. Items opened from it come back to it when closed.
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showCodes by rememberSaveable { mutableStateOf(false) }
    val shellStateHolder = rememberSaveableStateHolder()

    // First start: a short welcome. After updating to 2.0: what is new, once.
    val firstRunContext = LocalContext.current.applicationContext
    val featurePrefs = remember(firstRunContext) { FeaturePrefs(firstRunContext) }
    val features by featurePrefs.settings.collectAsStateWithLifecycle(null)
    val hasPasscode by AppContainer.lockManager(firstRunContext).hasPasscode.collectAsStateWithLifecycle()
    val vaultEmpty by produceState<Boolean?>(null) {
        value = withContext(Dispatchers.IO) {
            try {
                TazDatabase.getInstance(firstRunContext).openHelper.readableDatabase.query(
                    "SELECT (SELECT COUNT(*) FROM notes) + (SELECT COUNT(*) FROM credentials) + (SELECT COUNT(*) FROM files)"
                ).use { c -> c.moveToFirst() && c.getLong(0) == 0L }
            } catch (e: Exception) {
                false
            }
        }
    }
    val firstRunScope = rememberCoroutineScope()
    val loadedFeatures = features
    if (loadedFeatures == null || vaultEmpty == null) {
        Box(modifier.fillMaxSize())
        return
    }
    val brandNew = vaultEmpty == true && !hasPasscode
    if (!loadedFeatures.onboardingDone && brandNew && loadedFeatures.lastSeenVersion == 0) {
        WelcomeScreen { setUpLock ->
            firstRunScope.launch {
                featurePrefs.setLastSeenVersion(WHATS_NEW_VERSION)
                featurePrefs.setOnboardingDone()
            }
            if (setUpLock) currentPath = Route.Settings.path
        }
        return
    }
    if (loadedFeatures.lastSeenVersion < WHATS_NEW_VERSION && !brandNew) {
        WhatsNewScreen {
            firstRunScope.launch {
                featurePrefs.setLastSeenVersion(WHATS_NEW_VERSION)
                featurePrefs.setOnboardingDone()
            }
        }
        return
    }

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

    // The widget's "2FA codes" button, likewise only after unlocking.
    val codesRequestedAt by AppShortcuts.codesRequestedAt.collectAsState()
    LaunchedEffect(codesRequestedAt) {
        if (codesRequestedAt != 0L) {
            if (AppShortcuts.isFresh(codesRequestedAt, System.currentTimeMillis())) {
                editingNoteId = null
                editingCredentialId = null
                viewingFileId = null
                showCodes = true
            }
            AppShortcuts.clearCodes()
        }
    }

    // The same (activity-wide) instances the list screens use, so search can open a folder in them.
    val appContext = LocalContext.current.applicationContext
    val notesViewModel: NotesViewModel = viewModel(factory = NotesViewModel.factory(appContext))
    val passwordsViewModel: PasswordsViewModel = viewModel(factory = PasswordsViewModel.factory(appContext))
    val filesViewModel: FilesViewModel = viewModel(factory = FilesViewModel.factory(appContext))

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
        showSearch -> Screen.Search
        showPasswordHealth -> Screen.PasswordHealth
        showTrash -> Screen.Trash
        showTags -> Screen.Tags
        showCategories -> Screen.Categories
        showCodes -> Screen.Codes
        else -> Screen.Shell
    }

    val shellContent: @Composable () -> Unit = {
        ShellContent(
            shellStateHolder = shellStateHolder,
            snackbarHostState = snackbarHostState,
            currentRoute = currentRoute,
            onSelectRoute = { currentPath = it.path },
            onOpenSearch = { showSearch = true },
            onOpenNote = { editingNoteId = it },
            onOpenCredential = { editingCredentialId = it },
            onOpenFile = { viewingFileId = it },
            onOpenHealth = { showPasswordHealth = true },
            onOpenTrash = { showTrash = true },
            onOpenTags = { showTags = true },
            onOpenCategories = { showCategories = true },
            onOpenCodes = { showCodes = true },
            showMessage = showMessage
        )
    }

    // Tablets and wide landscape screens: the open note, login or file sits next to the list.
    val wide = LocalConfiguration.current.screenWidthDp >= TWO_PANE_MIN_WIDTH_DP
    if (wide && screen.depth == 2) {
        Row(modifier.fillMaxSize()) {
            Box(Modifier.width(LIST_PANE_WIDTH).fillMaxHeight()) { shellContent() }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (screen) {
                    is Screen.NoteEditor -> key(screen.id) { ContentFrame { NoteEditorScreen(noteId = screen.id, onClose = { editingNoteId = null }, modifier = Modifier.fillMaxSize()) } }
                    is Screen.PasswordEditor -> key(screen.id) { ContentFrame { PasswordEditorScreen(credentialId = screen.id, onClose = { editingCredentialId = null }, modifier = Modifier.fillMaxSize()) } }
                    is Screen.FileViewer -> key(screen.id) { ContentFrame { FileDetailScreen(fileId = screen.id, onClose = { viewingFileId = null }, modifier = Modifier.fillMaxSize()) } }
                    else -> Unit
                }
            }
        }
        return
    }

    AnimatedContent(
        targetState = screen,
        modifier = modifier,
        transitionSpec = {
            if (targetState.depth < initialState.depth) {
                // Going back: a quick fade with a small slide the other way, so it reads as a step back.
                (fadeIn(tween(160)) + slideInHorizontally(tween(200)) { -it / 20 }) togetherWith
                    (fadeOut(tween(110)) + slideOutHorizontally(tween(160)) { it / 20 })
            } else {
                // Opening something: fade in with a small slide so it reads as a step forward.
                (fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 14 }) togetherWith fadeOut(tween(120))
            }
        },
        label = "screen"
    ) { current ->
        when (current) {
            Screen.Search -> ContentFrame {
                SearchScreen(
                    onClose = { showSearch = false },
                    onOpenNote = { editingNoteId = it },
                    onOpenLogin = { editingCredentialId = it },
                    onOpenFile = { viewingFileId = it },
                    onOpenFolder = { folder ->
                        // Show the folder in its own section, exactly as if it had been opened there.
                        when (folder.module) {
                            FolderModule.NOTES.name -> {
                                notesViewModel.openFolder(folder.id)
                                currentPath = Route.Notes.path
                            }
                            FolderModule.PASSWORDS.name -> {
                                passwordsViewModel.openFolder(folder.id)
                                currentPath = Route.Passwords.path
                            }
                            else -> {
                                filesViewModel.openFolder(folder.id)
                                currentPath = Route.Files.path
                            }
                        }
                        showSearch = false
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
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
            Screen.Tags -> ContentFrame {
                TagsScreen(onClose = { showTags = false }, modifier = Modifier.fillMaxSize())
            }
            Screen.Categories -> ContentFrame {
                CategoriesScreen(onClose = { showCategories = false }, modifier = Modifier.fillMaxSize())
            }
            Screen.Shell -> shellContent()
            Screen.Codes -> ContentFrame {
                CodesScreen(
                    onClose = { showCodes = false },
                    onOpenLogin = { editingCredentialId = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** The bottom-navigation shell with the four lists. */
@Composable
private fun ShellContent(
    shellStateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    snackbarHostState: SnackbarHostState,
    currentRoute: Route,
    onSelectRoute: (Route) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenCredential: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenHealth: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenCodes: () -> Unit,
    showMessage: (String) -> Unit
) {
                // Keeps list scroll position and search text while an editor is open.
                shellStateHolder.SaveableStateProvider("shell") {
                    CompositionLocalProvider(LocalOpenSearch provides onOpenSearch) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            TazBottomBar(
                                current = currentRoute,
                                onSelect = onSelectRoute
                            )
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            ContentFrame {
                                Crossfade(targetState = currentRoute, animationSpec = tween(150), label = "tab") { route ->
                                    val screenModifier = Modifier.fillMaxSize()
                                    when (route) {
                                        Route.Notes -> NotesScreen(
                                            onOpenNote = onOpenNote,
                                            modifier = screenModifier
                                        )
                                        Route.Passwords -> PasswordsScreen(
                                            onOpenCredential = onOpenCredential,
                                            onOpenHealth = onOpenHealth,
                                            modifier = screenModifier
                                        )
                                        Route.Files -> FilesScreen(
                                            onOpenFile = onOpenFile,
                                            onMessage = showMessage,
                                            modifier = screenModifier
                                        )
                                        Route.Settings -> SettingsScreen(
                                            onMessage = showMessage,
                                            onOpenTrash = onOpenTrash,
                                            onOpenPasswordHealth = onOpenHealth,
                                            onOpenTags = onOpenTags,
                                            onOpenCategories = onOpenCategories,
                                            onOpenCodes = onOpenCodes,
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

/** How deep a screen sits: lists, then tools and search, then a single item. Decides the transition direction. */
private val Screen.depth: Int
    get() = when (this) {
        Screen.Shell -> 0
        Screen.Search, Screen.Trash, Screen.PasswordHealth, Screen.Tags, Screen.Categories, Screen.Codes -> 1
        is Screen.NoteEditor, is Screen.PasswordEditor, is Screen.FileViewer -> 2
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
