package dev.kodelab.ide.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.kodelab.ide.editor.EditorWebView
import dev.kodelab.ide.editor.Markdown
import dev.kodelab.ide.git.GitFileStatus
import dev.kodelab.ide.terminal.SandboxInstaller
import dev.kodelab.ide.terminal.ShellSession
import dev.kodelab.ide.terminal.TerminalEmulator
import dev.kodelab.ide.terminal.TerminalScheme
import dev.kodelab.ide.terminal.TerminalSchemes
import dev.kodelab.ide.terminal.TerminalHost
import dev.kodelab.ide.terminal.TerminalKeys
import dev.kodelab.ide.theme.EditorPalette
import dev.kodelab.ide.theme.KodelabThemes
import dev.kodelab.ide.theme.LocalEditorPalette
import dev.kodelab.ide.workspace.WorkspaceRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One motion curve for every panel that opens, closes or resizes. */
private val PANEL_SPEC = tween<Float>(durationMillis = 200)

/** How long a newly-arrived rail button glows for. */
private const val GLOW_MILLIS = 2000L
private val PANEL_SPEC_INT = tween<androidx.compose.ui.unit.IntSize>(durationMillis = 200)
private val PANEL_SPEC_OFFSET = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 200)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdeScaffold(state: IdeUiState, actions: IdeActions, viewModel: IdeViewModel) {
    val palette = LocalEditorPalette.current
    var settingsOpen by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalUiScale provides state.presets.uiScale) {
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.surface)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            if (!state.fullScreen) {
                ActivityRail(state, actions, viewModel, onOpenSettings = { settingsOpen = true })
            }
            // The side panel pushes the editor and terminal off the edge of the
            // screen rather than squeezing them into what's left. Re-flowing
            // that pane to a phone-minus-panel width rewrapped every line in it
            // — the terminal's own header among them — and then rewrapped them
            // all back when the panel closed. Off the edge is where a narrow
            // window goes; the layout underneath doesn't change at all.
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val paneWidth = maxWidth
            Row(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = state.sidebarVisible && !state.fullScreen,
                enter = expandHorizontally(PANEL_SPEC_INT, expandFrom = Alignment.Start) + fadeIn(PANEL_SPEC),
                exit = shrinkHorizontally(PANEL_SPEC_INT, shrinkTowards = Alignment.Start) + fadeOut(PANEL_SPEC),
            ) {
                SidePanel(state, actions, viewModel, Modifier.width(scaled(240.dp)).fillMaxHeight())
            }
            // requiredWidth, not weight: the pane keeps the width it has when
            // the panel is closed, and the overflow is clipped by the box.
            Column(Modifier.requiredWidth(paneWidth).fillMaxHeight()) {
                // A maximised terminal takes the whole column so you can focus on
                // it; the editor (and its tab bar) step aside until you restore.
                val fullTerminal = state.panelVisible && state.terminalMaximized
                AnimatedVisibility(
                    visible = !fullTerminal && !state.fullScreen,
                    enter = expandVertically(PANEL_SPEC_INT) + fadeIn(PANEL_SPEC),
                    exit = shrinkVertically(PANEL_SPEC_INT) + fadeOut(PANEL_SPEC),
                ) {
                    TabBar(state, actions)
                }
                Box(
                    if (fullTerminal) Modifier.size(1.dp)
                    else Modifier.weight(1f).fillMaxWidth().background(palette.surface),
                ) {
                    // Native views always draw above Compose content, so the
                    // WebView is shrunk (not removed) while reading — that keeps
                    // every open Monaco buffer, and its unsaved edits, alive.
                    val reading = state.readerTabId != null && state.readerTabId == state.activeTabId
                    val empty = state.activeTabId == null
                    EditorWebView(
                        controller = viewModel.editor,
                        palette = palette,
                        onEvent = actions::onWebEvent,
                        modifier = if (reading || fullTerminal || empty) Modifier.size(1.dp)
                        else Modifier.fillMaxSize(),
                        editMode = state.presets.editMode && !reading && !fullTerminal,
                        // Tapping the code puts the file back in charge of the
                        // screen: on a phone the panel is most of the width, and
                        // reaching back up to the rail icon to close it is a
                        // trip you shouldn't have to make.
                        onTap = { if (state.sidebarVisible) actions.toggleSidebar() },
                    )
                    // Not while a restore is in flight: showing the guide and
                    // then replacing it is the flicker this avoids.
                    if (empty && !fullTerminal && !state.restoring) {
                        WelcomeGuide(
                            onOpenFolder = viewModel::requestOpenFolder,
                            onOpenSandboxHome = viewModel::openSandboxHome,
                            onOpenSettings = { settingsOpen = true },
                            onOpenTerminal = actions::togglePanel,
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = reading && !fullTerminal,
                        enter = fadeIn(PANEL_SPEC),
                        exit = fadeOut(PANEL_SPEC),
                    ) {
                        MarkdownReader(
                            source = state.readerText,
                            baseFontSizeSp = state.presets.fontSizeSp,
                            onLinkClick = actions::readerLinkClicked,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Following a reference has to be reversible: this is the
                    // way back (and forward again) without a keyboard.
                    if (!empty && !fullTerminal) {
                        Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                            EditorFloatingNav(state, actions)
                        }
                    }
                    // Drawn last, so the reader — which fills this box — can't
                    // cover the only way out of full screen.
                    if (state.fullScreen) {
                        Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                            FullScreenExitButton(onClick = actions::toggleFullScreen)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = state.panelVisible && !state.fullScreen,
                    enter = slideInVertically(PANEL_SPEC_OFFSET) { it } + expandVertically(PANEL_SPEC_INT) + fadeIn(PANEL_SPEC),
                    exit = slideOutVertically(PANEL_SPEC_OFFSET) { it } + shrinkVertically(PANEL_SPEC_INT) + fadeOut(PANEL_SPEC),
                    modifier = if (fullTerminal) Modifier.weight(1f) else Modifier,
                ) {
                    TerminalPanel(
                        state,
                        actions,
                        if (fullTerminal) Modifier.fillMaxSize() else Modifier.height(230.dp).fillMaxWidth(),
                    )
                }
            }
            } // Row: side panel + pane
            } // BoxWithConstraints
        }
        AnimatedVisibility(
            visible = WindowInsets.isImeVisible && !state.panelVisible,
            enter = slideInVertically(PANEL_SPEC_OFFSET) { it } + fadeIn(PANEL_SPEC),
            exit = slideOutVertically(PANEL_SPEC_OFFSET) { it } + fadeOut(PANEL_SPEC),
        ) {
            EditorAccessoryBar(actions)
        }
        if (!state.fullScreen) StatusBar(state, actions)
    }

    if (settingsOpen) SettingsDialog(state, actions, viewModel, onDismiss = { settingsOpen = false })

    if (state.paletteOpen) CommandPalette(state, actions)

    state.pendingFileOp?.let { op -> FileOpDialog(op, actions) }

    state.pendingCloseTabId?.let { tabId ->
        val tab = state.tabs.firstOrNull { it.id == tabId }
        AlertDialog(
            onDismissRequest = actions::cancelClose,
            title = { Text("Discard changes?") },
            text = { Text("“${tab?.title ?: "This file"}” has unsaved changes. Closing the tab discards them.") },
            confirmButton = {
                TextButton(onClick = actions::confirmCloseDiscard) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = actions::cancelClose) { Text("Keep editing") }
            },
        )
    }
    } // LocalUiScale
}

@Composable
private fun ActivityRail(
    state: IdeUiState,
    actions: IdeActions,
    viewModel: IdeViewModel,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    // Offered only for a README — the palette's "Toggle Markdown reader" still
    // renders any .md — and first in the rail, where it's reachable by thumb.
    val readmeTab = state.tabs.firstOrNull { it.id == state.activeTabId }
        ?.let { Markdown.isReadme(it.title) } == true
    // In landscape there isn't height for ten 48dp buttons, and the overflow
    // was simply clipped — the bottom half of the rail vanished. Measure first:
    // pin the bottom group when it fits, scroll when it doesn't.
    BoxWithConstraints(Modifier.width(scaled(48.dp)).fillMaxHeight().background(palette.chrome)) {
    val buttons = 10
    val fits = maxHeight >= scaled(48.dp) * buttons
    Column(
        Modifier
            .fillMaxSize()
            .then(if (fits) Modifier else Modifier.verticalScroll(rememberScrollState())),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Tapping the active view collapses the side panel (VS Code behaviour),
        // so the panel is dismissable on a small screen.
        fun onView(view: SidebarView) {
            if (state.sidebarVisible && state.sidebarView == view) actions.toggleSidebar()
            else actions.setSidebarView(view)
        }
        fun activeView(view: SidebarView) = state.sidebarVisible && state.sidebarView == view
        RailButton(Icons.Filled.FolderOpen, activeView(SidebarView.EXPLORER), "Explorer") {
            onView(SidebarView.EXPLORER)
        }
        RailButton(Icons.Filled.Search, activeView(SidebarView.SEARCH), "Search") {
            onView(SidebarView.SEARCH)
        }
        RailButton(Icons.Filled.Source, activeView(SidebarView.GIT), "Git") {
            onView(SidebarView.GIT)
        }
        RailButton(Icons.Filled.Extension, activeView(SidebarView.EXTENSIONS), "Extensions") {
            onView(SidebarView.EXTENSIONS)
        }
        if (fits) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(10.dp))
        // Appears only for a README, so it needs to announce itself: it fades
        // and scales in, then pulses its accent twice so the change is noticed.
        AnimatedVisibility(
            visible = readmeTab,
            enter = fadeIn(PANEL_SPEC) + scaleIn(PANEL_SPEC, initialScale = 0.6f),
            exit = fadeOut(PANEL_SPEC) + scaleOut(PANEL_SPEC, targetScale = 0.6f),
        ) {
            val reading = state.readerTabId != null
            RailButton(
                Icons.AutoMirrored.Filled.MenuBook,
                reading,
                if (reading) "Back to the editor" else "Read as a document",
                highlight = !reading,
            ) { actions.toggleReader() }
        }
        val editing = state.presets.editMode
        RailButton(
            if (editing) Icons.Filled.Edit else Icons.Filled.Visibility,
            editing,
            if (editing) "Edit mode (tap for reading mode)" else "Reading mode (tap to edit)",
        ) { actions.toggleEditMode() }
        RailButton(Icons.Filled.Fullscreen, false, "Full screen (hide all menus)") { actions.toggleFullScreen() }
        RailButton(Icons.Filled.Save, false, "Save file") { actions.saveActiveTab() }
        RailButton(Icons.Filled.Terminal, state.panelVisible, "Terminal") { actions.togglePanel() }
        RailButton(Icons.AutoMirrored.Filled.OpenInNew, false, "New window") { viewModel.requestNewWindow() }
        RailButton(Icons.Filled.Settings, false, "Settings") { onOpenSettings() }
    }
    }
}

/**
 * The floating cluster in the bottom corner of the editor: back, forward, and
 * find-in-file.
 *
 * These are the three moves a phone has no key for. Back and forward walk the
 * navigation history — what alt-← does on a desktop, and the thing that was
 * missing after following a reference into another file. Find opens Monaco's
 * own search box, which otherwise needs ctrl-F.
 *
 * The pair stays in place and greys out when there is nowhere to go, rather
 * than appearing and disappearing under the thumb that is reaching for it.
 */
@Composable
private fun EditorFloatingNav(state: IdeUiState, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    Row(
        Modifier
            .shadow(6.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(palette.chrome.copy(alpha = 0.94f))
            .border(1.dp, palette.border, RoundedCornerShape(22.dp))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FloatingNavButton(
            Icons.AutoMirrored.Filled.ArrowBack,
            "Back",
            enabled = state.canNavigateBack,
            onClick = actions::navigateBack,
        )
        FloatingNavButton(
            Icons.AutoMirrored.Filled.ArrowForward,
            "Forward",
            enabled = state.canNavigateForward,
            onClick = actions::navigateForward,
        )
        Spacer(Modifier.width(1.dp).height(scaled(22.dp)).background(palette.border))
        FloatingNavButton(
            Icons.Filled.KeyboardDoubleArrowUp,
            "Top of file",
            enabled = true,
            onClick = { actions.scrollEditorTo("top") },
        )
        FloatingNavButton(
            Icons.Filled.KeyboardDoubleArrowDown,
            "Bottom of file",
            enabled = true,
            onClick = { actions.scrollEditorTo("bottom") },
        )
        Spacer(Modifier.width(1.dp).height(scaled(22.dp)).background(palette.border))
        FloatingNavButton(
            Icons.Filled.Search,
            "Find in this file",
            enabled = true,
            onClick = actions::findInFile,
        )
    }
}

@Composable
private fun FloatingNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    WithTooltip(label) {
        Box(
            Modifier
                .size(scaled(40.dp))
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) palette.textPrimary else palette.textMuted.copy(alpha = 0.4f),
                modifier = Modifier.size(scaled(20.dp)),
            )
        }
    }
}

/** A round, shadowed button in the corner — the back-to-top shape, so it reads
 *  as a floating control rather than as part of the document. */
@Composable
private fun FullScreenExitButton(onClick: () -> Unit) {
    val palette = LocalEditorPalette.current
    WithTooltip("Exit full screen") {
        Box(
            Modifier
                .size(scaled(48.dp))
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(palette.accent)
                .border(1.dp, palette.border, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.FullscreenExit,
                contentDescription = "Exit full screen",
                tint = Color.White,
                modifier = Modifier.size(scaled(26.dp)),
            )
        }
    }
}

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    label: String,
    /** Pulse the icon's colour, to catch the eye when the button appears. */
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    val resting = if (active) palette.textPrimary else palette.textMuted
    // Glow briefly on arrival, then settle: a permanent pulse reads as an
    // error state rather than "this is new".
    var glowing by remember(highlight) { mutableStateOf(highlight) }
    LaunchedEffect(highlight) {
        if (!highlight) return@LaunchedEffect
        glowing = true
        kotlinx.coroutines.delay(GLOW_MILLIS)
        glowing = false
    }
    val tint = if (!glowing) resting else {
        val pulse = rememberInfiniteTransition(label = "rail-pulse")
        val t by pulse.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
            label = "rail-pulse-fraction",
        )
        lerp(resting, palette.accent, t)
    }
    Box(Modifier.size(scaled(48.dp)), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier.width(2.dp).fillMaxHeight().background(palette.accent)
                    .align(Alignment.CenterStart),
            )
        }
        ChromeIconButton(icon, label, onClick, baseSize = 44.dp, baseIconSize = 20.dp, tint = tint)
    }
}

// ---------- side panel ----------

@Composable
private fun SidePanel(
    state: IdeUiState,
    actions: IdeActions,
    viewModel: IdeViewModel,
    modifier: Modifier,
) {
    val palette = LocalEditorPalette.current
    Column(modifier.background(palette.panel).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.sidebarView) {
                    SidebarView.EXPLORER -> state.workspaceName.uppercase()
                    else -> state.sidebarView.name
                },
                color = palette.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (state.sidebarView == SidebarView.EXPLORER) {
                if (state.workspaceUri != null) {
                    ChromeIconButton(Icons.Filled.NoteAdd, "New file", { actions.requestFileOp(FileOpKind.NEW_FILE, null) })
                    ChromeIconButton(Icons.Filled.CreateNewFolder, "New folder", { actions.requestFileOp(FileOpKind.NEW_FOLDER, null) })
                    ChromeIconButton(Icons.Filled.UnfoldLess, "Collapse all folders", { actions.collapseAll() })
                }
                var openMenu by remember { mutableStateOf(false) }
                Box {
                    ChromeIconButton(Icons.Filled.FolderOpen, "Open folder", { openMenu = true })
                    DropdownMenu(expanded = openMenu, onDismissRequest = { openMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open folder…") },
                            onClick = { openMenu = false; viewModel.requestOpenFolder() },
                        )
                        DropdownMenuItem(
                            text = { Text("Open from terminal (Alpine home)") },
                            onClick = { openMenu = false; viewModel.openSandboxHome() },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        when (state.sidebarView) {
            SidebarView.EXPLORER -> ExplorerTree(state, actions)
            SidebarView.SEARCH -> SearchPanel(state, actions)
            SidebarView.GIT -> GitPanel(state, actions)
            SidebarView.EXTENSIONS -> ExtensionsPanel(state)
        }
    }
}

@Composable
private fun SearchPanel(state: IdeUiState, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    Column(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = state.searchQuery,
            onValueChange = actions::searchQueryChanged,
            textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.sp),
            cursorBrush = SolidColor(palette.accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { actions.runSearch() }),
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().background(palette.surface, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Search, contentDescription = null,
                        tint = palette.textMuted, modifier = Modifier.size(15.dp),
                    )
                    Box(Modifier.weight(1f)) {
                        if (state.searchQuery.isEmpty()) {
                            Text("Search in files…", color = palette.textMuted, fontSize = 13.sp)
                        }
                        inner()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        val summary = when {
            state.searching -> "Searching…"
            else -> state.searchSummary
        }
        summary?.let {
            Text(
                it, color = palette.textMuted, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            state.searchResults.forEach { file ->
                item(key = "f:" + file.path) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.Description, contentDescription = null,
                            tint = palette.textMuted, modifier = Modifier.size(13.dp),
                        )
                        Text(
                            file.name, color = palette.textPrimary, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                        )
                        Text(
                            file.matches.size.toString(), color = palette.textMuted, fontSize = 11.sp,
                        )
                    }
                }
                items(file.matches, key = { m -> "m:" + file.path + ":" + m.line + ":" + m.start }) { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { actions.openSearchHit(file, m) }
                            .padding(start = 18.dp, top = 3.dp, bottom = 3.dp, end = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            m.line.toString(), color = palette.textMuted, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            searchHitLine(m.text, m.start, m.end, palette),
                            fontSize = 12.sp, maxLines = 1,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

private fun searchHitLine(
    text: String,
    start: Int,
    end: Int,
    palette: EditorPalette,
): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    // Trim leading whitespace for readability but keep the match visible.
    val leading = text.takeWhile { it == ' ' || it == '\t' }.length
    val s = (start - leading).coerceIn(0, text.length)
    val e = (end - leading).coerceIn(s, text.length)
    val body = text.drop(leading)
    pushStyle(androidx.compose.ui.text.SpanStyle(color = palette.textMuted))
    append(body.take(s))
    pop()
    pushStyle(
        androidx.compose.ui.text.SpanStyle(
            color = palette.textPrimary,
            background = palette.accentMuted,
            fontWeight = FontWeight.Bold,
        ),
    )
    append(body.substring(s, e))
    pop()
    pushStyle(androidx.compose.ui.text.SpanStyle(color = palette.textMuted))
    append(body.drop(e))
    pop()
}

@Composable
private fun ExtensionsPanel(state: IdeUiState) {
    val palette = LocalEditorPalette.current
    if (state.workspaceUri == null) {
        PanelPlaceholder("Open a folder to see its extensions.")
        return
    }
    if (state.extensions.isEmpty()) {
        // Wrapped by the layout, not by hand: the hand-placed breaks were cut
        // for one panel width and read as ragged everywhere else.
        PanelPlaceholder(
            "No extensions in this workspace.\n\n" +
                "Drop declarative add-ons under .kodelab/extensions/<id>/ with a " +
                "kodelab-extension.json manifest (themes, snippets, grammars, LSP recipes).\n\n" +
                "Each is license-audited (SPDX); only permissively licensed ones " +
                "activate. Kodelab never uses the Microsoft Marketplace.",
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(state.extensions, key = { it.id }) { ext ->
            Column(
                Modifier.fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        Icons.Filled.Extension, contentDescription = null,
                        tint = if (ext.allowed) palette.accentMuted else palette.warn,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(ext.name, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(
                        if (ext.allowed) "active" else "flagged",
                        color = if (ext.allowed) palette.good else palette.warn, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                val meta = listOfNotNull(
                    "v${ext.version}",
                    ext.publisher,
                    ext.license ?: "no license",
                ).joinToString(" · ")
                Text(meta, color = palette.textMuted, fontSize = 10.sp, maxLines = 1)
                ext.description?.let {
                    Text(it, color = palette.textMuted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3)
                }
                Text(ext.summary, color = palette.textMuted, fontSize = 11.sp)
                if (!ext.allowed && ext.issues.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    ext.issues.forEach { issue ->
                        Text("• $issue", color = palette.warn, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

/**
 * What you see with nothing open. It sits in the editor area rather than the
 * side panel, so it's the first thing read on launch — and the side panel stays
 * closed until there's actually a folder in it.
 */
@Composable
private fun WelcomeGuide(
    onOpenFolder: () -> Unit,
    onOpenSandboxHome: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    Column(
        Modifier
            .fillMaxSize()
            // the same colour as the tab strip above it and the launch window,
            // so there's no seam between them
            .background(palette.panel)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Kodelab", color = palette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "An IDE that runs on this device, with a real Linux terminal.",
            color = palette.textMuted, fontSize = 13.sp,
        )

        GuideAction(
            icon = Icons.Filled.FolderOpen,
            title = "Open a folder",
            detail = "Pick one with the system file picker. This is the same folder icon as the one in the side panel.",
            onClick = onOpenFolder,
        )
        GuideAction(
            icon = Icons.Filled.Terminal,
            title = "Open the terminal",
            detail = "Install Linux once, then clone a repo, run git, install what you need.",
            onClick = onOpenTerminal,
        )
        GuideAction(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = "Open Alpine home",
            detail = "Browse a repo you cloned in the terminal — the terminal and Git can work on those.",
            onClick = onOpenSandboxHome,
        )
        GuideAction(
            icon = Icons.Filled.Settings,
            title = "Check Settings",
            detail = "Theme, editor and terminal font size, word wrap, terminal colours and touch-target size.",
            onClick = onOpenSettings,
        )
    }
}

/** One tappable row of the guide: the real icon, what it does, and why. */
@Composable
private fun GuideAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // the page is panel-coloured now, so the cards lift off it
            .background(palette.overlay)
            .border(1.dp, palette.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = palette.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

/** First-run guidance: what to tap, shown with the icon you actually tap. */
@Composable
private fun GettingStarted() {
    val palette = LocalEditorPalette.current
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "No folder open",
            color = palette.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.FolderOpen, contentDescription = null,
                tint = palette.accent, modifier = Modifier.size(20.dp),
            )
            Text(
                "Tap this icon at the top of this panel to open a folder.",
                color = palette.textMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.Settings, contentDescription = null,
                tint = palette.accent, modifier = Modifier.size(20.dp),
            )
            Text(
                "Settings, at the bottom of the rail, has the theme, font sizes, " +
                    "word wrap and touch-target size.",
                color = palette.textMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.Terminal, contentDescription = null,
                tint = palette.accent, modifier = Modifier.size(20.dp),
            )
            Text(
                "The terminal runs a real Linux sandbox — clone a repo there and " +
                    "open it from Kodelab (Alpine home) in the picker.",
                color = palette.textMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun PanelPlaceholder(text: String) {
    val palette = LocalEditorPalette.current
    Text(text, color = palette.textMuted, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun GitPanel(state: IdeUiState, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    val git = state.git
    // Non-ready states get a short explanation and, where useful, an action.
    if (git.availability != GitAvailability.READY || git.status == null) {
        val msg = when (git.availability) {
            GitAvailability.UNKNOWN -> if (git.loading) "Checking repository…" else "Open the Git panel to check the repository."
            GitAvailability.SANDBOX_MISSING ->
                "Git runs in the Linux sandbox. Open the terminal and tap “Install Linux”, then “apk add git”."
            GitAvailability.GIT_MISSING ->
                "Git isn't installed in the sandbox yet. In the terminal run:\n\n    apk add git"
            GitAvailability.NO_PATH ->
                "This folder is shared from another app, so it has no path the Linux " +
                    "sandbox can open — only that app's provider can read it, which " +
                    "git and the terminal can't use.\n\n" +
                    "To work on it here: clone it in the terminal, or open a folder " +
                    "from Kodelab (Alpine home) in the picker's menu."
            GitAvailability.NOT_A_REPO ->
                "No git repository here.\n\nIn the terminal you can run:\n\n    git init"
            GitAvailability.ERROR -> "git error:\n\n${git.message ?: "unknown"}"
            GitAvailability.READY -> ""
        }
        Column {
            GitPanelHeader(branchText = null, loading = git.loading, actions = actions)
            Spacer(Modifier.height(8.dp))
            PanelPlaceholder(msg)
        }
        return
    }

    val status = git.status
    val branchText = buildString {
        append(status.branch ?: if (status.detached) "detached" else "—")
        if (status.ahead > 0) append("  ↑${status.ahead}")
        if (status.behind > 0) append("  ↓${status.behind}")
    }
    Column(Modifier.fillMaxWidth()) {
        GitPanelHeader(branchText = branchText, loading = git.loading, actions = actions)
        Spacer(Modifier.height(6.dp))

        if (git.readOnly) {
            // Read straight from .git: real status, but nothing that writes.
            Text(
                "Read-only — this folder is shared from another app, so status " +
                    "comes from .git directly. Staging and committing need the " +
                    "terminal's git.",
                color = palette.warn, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        // Commit box
        if (!git.readOnly) BasicTextField(
            value = git.commitMessage,
            onValueChange = actions::gitCommitMessageChanged,
            textStyle = TextStyle(color = palette.textPrimary, fontSize = 13.sp),
            cursorBrush = SolidColor(palette.accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.gitCommit() }),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().background(palette.surface, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    if (git.commitMessage.isEmpty()) {
                        Text("Message (⏎ to commit)", color = palette.textMuted, fontSize = 13.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        git.message?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = palette.textMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))

        val staged = status.staged
        val unstaged = status.unstaged
        LazyColumn(Modifier.fillMaxWidth()) {
            if (status.clean) {
                item { PanelPlaceholder("Nothing to commit — working tree clean.") }
            }
            if (staged.isNotEmpty()) {
                item(key = "hdr-staged") { GitSectionHeader("Staged Changes", staged.size) }
                items(staged, key = { "s:" + it.path }) { f ->
                    GitFileRow(f, staged = true, busy = git.busyPath == f.path,
                        actionable = !git.readOnly,
                        onOpen = { actions.openGitDiff(f, staged = true) },
                        onAction = { actions.gitUnstage(f) })
                }
            }
            if (unstaged.isNotEmpty()) {
                item(key = "hdr-changes") {
                    GitSectionHeader(
                        "Changes", unstaged.size,
                        onStageAll = if (git.readOnly) null else actions::gitStageAll,
                    )
                }
                items(unstaged, key = { "u:" + it.path }) { f ->
                    GitFileRow(f, staged = false, busy = git.busyPath == f.path,
                        actionable = !git.readOnly,
                        onOpen = { actions.openGitDiff(f, staged = false) },
                        onAction = { actions.gitStage(f) })
                }
            }
        }
    }
}

@Composable
private fun GitPanelHeader(branchText: String?, loading: Boolean, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Source, contentDescription = null, tint = palette.accentMuted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            branchText ?: "Git",
            color = palette.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = actions::gitRefresh, modifier = Modifier.size(scaled(26.dp))) {
            Icon(
                Icons.Filled.Refresh, contentDescription = "Refresh",
                tint = if (loading) palette.accent else palette.textMuted, modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun GitSectionHeader(title: String, count: Int, onStageAll: (() -> Unit)? = null) {
    val palette = LocalEditorPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(), color = palette.textMuted, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f),
        )
        Text("$count", color = palette.textMuted, fontSize = 10.sp)
        if (onStageAll != null && count > 0) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onStageAll, modifier = Modifier.size(scaled(20.dp))) {
                Icon(Icons.Filled.Add, contentDescription = "Stage all", tint = palette.textMuted, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun GitFileRow(
    file: GitFileStatus,
    staged: Boolean,
    busy: Boolean,
    /** False when status was read from .git directly: nothing here can write. */
    actionable: Boolean = true,
    onOpen: () -> Unit,
    onAction: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    val badgeColor = when (file.badge()) {
        'M' -> palette.accent
        'A' -> palette.accentMuted
        'D' -> palette.textMuted
        'U' -> palette.accent
        '!' -> palette.textPrimary
        else -> palette.textMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = !busy, onClick = onOpen)
            .padding(start = 4.dp, top = 3.dp, bottom = 3.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            file.badge().toString(), color = badgeColor, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                file.path.substringAfterLast('/'), color = palette.textPrimary, fontSize = 12.sp, maxLines = 1,
            )
            if (file.path.contains('/')) {
                Text(
                    file.path.substringBeforeLast('/'), color = palette.textMuted, fontSize = 10.sp, maxLines = 1,
                )
            }
        }
        if (actionable) {
            IconButton(onClick = onAction, enabled = !busy, modifier = Modifier.size(scaled(24.dp))) {
                Icon(
                    if (staged) Icons.Filled.Remove else Icons.Filled.Add,
                    contentDescription = if (staged) "Unstage" else "Stage",
                    tint = palette.textMuted, modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun ExplorerTree(state: IdeUiState, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    if (state.fileTree.isEmpty()) {
        GettingStarted()
        return
    }
    val flat = remember(state.fileTree) { flattenVisible(state.fileTree) }
    LazyColumn {
        items(flat, key = { it.docId }) { node -> FileTreeRow(node, actions) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(node: FileNode, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .combinedClickable(
                    onClick = { if (node.isDir) actions.toggleDir(node) else actions.openFile(node) },
                    onLongClick = { menuOpen = true },
                )
                .heightIn(min = scaled(28.dp))
                .padding(start = (node.depth * 12).dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                when {
                    node.isDir && node.expanded -> Icons.Filled.ExpandMore
                    node.isDir -> Icons.Filled.ChevronRight
                    else -> Icons.Filled.Description
                },
                contentDescription = null,
                tint = if (node.isDir) palette.accentMuted else palette.textMuted,
                modifier = Modifier.size(scaled(14.dp)),
            )
            Text(node.name, color = palette.textPrimary, fontSize = 13.sp, maxLines = 1)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (node.isDir) {
                DropdownMenuItem(text = { Text("New file") }, onClick = {
                    menuOpen = false; actions.requestFileOp(FileOpKind.NEW_FILE, node)
                })
                DropdownMenuItem(text = { Text("New folder") }, onClick = {
                    menuOpen = false; actions.requestFileOp(FileOpKind.NEW_FOLDER, node)
                })
            }
            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                menuOpen = false; actions.requestFileOp(FileOpKind.RENAME, node)
            })
            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                menuOpen = false; actions.requestFileOp(FileOpKind.DELETE, node)
            })
        }
    }
}

@Composable
private fun FileOpDialog(op: FileOpRequest, actions: IdeActions) {
    var name by remember(op) { mutableStateOf(op.initialName) }
    val title = when (op.kind) {
        FileOpKind.NEW_FILE -> "New file" + (op.target?.let { " in ${it.name}/" } ?: "")
        FileOpKind.NEW_FOLDER -> "New folder" + (op.target?.let { " in ${it.name}/" } ?: "")
        FileOpKind.RENAME -> "Rename ${op.target?.name}"
        FileOpKind.DELETE -> "Delete ${op.target?.name}?"
    }
    AlertDialog(
        onDismissRequest = actions::cancelFileOp,
        title = { Text(title) },
        text = {
            if (op.kind == FileOpKind.DELETE) {
                Text(
                    if (op.target?.isDir == true) "The folder and everything inside it will be deleted."
                    else "The file will be deleted.",
                )
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = op.kind == FileOpKind.DELETE || name.isNotBlank(),
                onClick = { actions.confirmFileOp(name.trim()) },
            ) { Text(if (op.kind == FileOpKind.DELETE) "Delete" else "OK") }
        },
        dismissButton = { TextButton(onClick = actions::cancelFileOp) { Text("Cancel") } },
    )
}

/** Keys Monaco needs that the soft keyboard makes painful (REQ 2 comfort). */
@Composable
private fun EditorAccessoryBar(actions: IdeActions) {
    val palette = LocalEditorPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(scaled(38.dp))
            .background(palette.chrome)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AccessoryKey("⇥") { actions.sendEditorCommand("tab") }
        AccessoryKey("↶") { actions.sendEditorCommand("undo") }
        AccessoryKey("↷") { actions.sendEditorCommand("redo") }
        AccessoryKey("←") { actions.sendEditorCommand("cursorLeft") }
        AccessoryKey("↑") { actions.sendEditorCommand("cursorUp") }
        AccessoryKey("↓") { actions.sendEditorCommand("cursorDown") }
        AccessoryKey("→") { actions.sendEditorCommand("cursorRight") }
        AccessoryKey("⇤") { actions.sendEditorCommand("cursorHome") }
        AccessoryKey("⇥|") { actions.sendEditorCommand("cursorEnd") }
        listOf("{", "}", "(", ")", "[", "]", ";", ":", "=", "\"", "'", "<", ">", "!", "&", "|", "$", "_").forEach { s ->
            AccessoryKey(s) { actions.sendEditorText(s) }
        }
    }
}

/** An armed-or-not modifier key: lit while it's waiting for the next tap. */
/** One session's screen, scrolled to the bottom as it grows. */
@Composable
private fun TerminalOutput(
    session: dev.kodelab.ide.terminal.ShellSession?,
    scheme: TerminalScheme,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    hint: String,
    onTap: () -> Unit,
) {
    val screen by (session?.screen
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<List<TerminalEmulator.Span>>()) })
        .collectAsState()
    val cursor by (session?.cursor
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(ShellSession.Cursor(0, 0, false)) })
        .collectAsState()
    val scroll = rememberScrollState()
    LaunchedEffect(screen) { scroll.scrollTo(scroll.maxValue) }

    val measurer = rememberTextMeasurer()
    val textStyle = remember(fontSize, lineHeight, scheme) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            lineHeight = lineHeight,
        )
    }
    // A terminal has to tell the program its real size (TIOCSWINSZ), or every
    // full-screen program lays itself out for someone else's window. Measure
    // the monospace cell once, then hand the pty the rows/cols that fit.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
    ) {
        val density = LocalDensity.current
        val cell = remember(textStyle) { measurer.measure("M".repeat(20), textStyle).size }
        val charWidth = (cell.width / 20f).coerceAtLeast(1f)
        val rowHeight = cell.height.toFloat().coerceAtLeast(1f)
        val cols = with(density) {
            ((maxWidth - PADDING * 2).toPx() / charWidth).toInt().coerceIn(20, 500)
        }
        val rows = with(density) {
            ((maxHeight - PADDING * 2).toPx() / rowHeight).toInt().coerceIn(4, 200)
        }
        LaunchedEffect(session, rows, cols) { session?.resize(rows, cols) }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .horizontalScroll(rememberScrollState())
                .padding(PADDING),
        ) {
            if (screen.isEmpty()) {
                Text(
                    hint,
                    color = scheme.foreground.copy(alpha = 0.6f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                )
            }
            // SelectionContainer is what gives the output a long-press
            // selection and the system's Copy button — a terminal you can't
            // quote from is half a terminal. Links inside it stay tappable.
            SelectionContainer {
                Text(
                    text = remember(screen, scheme, cursor) {
                        screenToAnnotated(screen, scheme, cursor)
                    },
                    style = textStyle,
                    color = scheme.foreground,
                    softWrap = false,
                )
            }
        }
    }
}

/** Terminal body inset — also what the rows/cols measurement subtracts. */
private val PADDING = 8.dp

/** Chrome-style tabs for the shared terminal sessions, with a "+" to spawn one. */
@Composable
private fun TerminalTabStrip(
    ids: List<String>,
    activeIndex: Int,
    deadIds: Set<String>,
    onSelect: (Int) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.panel)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ids.forEachIndexed { i, id ->
            val active = i == activeIndex
            val dead = id in deadIds
            Row(
                // Same metrics as the editor's document tabs (see TabBar), so
                // the two strips read as one tab system rather than two.
                Modifier
                    .height(scaled(36.dp))
                    .background(if (active) palette.tabActive else palette.tabInactive)
                    .clickable { onSelect(i) }
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    (if (id == TerminalHost.DEFAULT_SESSION_ID) "shell" else "shell ${i + 1}") +
                        if (dead) " (exited)" else "",
                    color = when {
                        dead -> palette.textMuted
                        active -> palette.textPrimary
                        else -> palette.textMuted
                    },
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                // every tab closes, including the last: an empty panel offers "+"
                Box(
                    Modifier.size(scaled(20.dp)).clip(RoundedCornerShape(3.dp))
                        .clickable { onClose(id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Close ${'$'}id",
                        tint = palette.textMuted, modifier = Modifier.size(scaled(12.dp)),
                    )
                }
            }
            Spacer(Modifier.width(1.dp).height(scaled(36.dp)).background(palette.border))
        }
        ChromeIconButton(
            Icons.Filled.Add, "New terminal", onNew,
            baseSize = 36.dp, baseIconSize = 18.dp,
        )
    }
}

@Composable
private fun ModifierKey(label: String, armed: Boolean, onClick: () -> Unit) {
    val palette = LocalEditorPalette.current
    Box(
        Modifier
            .size(width = scaled(44.dp), height = scaled(30.dp))
            .clip(RoundedCornerShape(5.dp))
            .background(if (armed) palette.accent else palette.panel)
            .border(1.dp, if (armed) palette.accent else palette.border, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (armed) Color.White else palette.textPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun AccessoryKey(label: String, half: Boolean = false, onClick: () -> Unit) {
    val palette = LocalEditorPalette.current
    Box(
        Modifier
            .size(width = scaled(40.dp), height = scaled(if (half) 14.dp else 30.dp))
            .clip(RoundedCornerShape(5.dp))
            .background(palette.panel)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = palette.textPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun flattenVisible(nodes: List<FileNode>): List<FileNode> =
    nodes.flatMap { n ->
        if (n.isDir && n.expanded && n.children != null) listOf(n) + flattenVisible(n.children)
        else listOf(n)
    }

// ---------- tabs ----------

@Composable
private fun TabBar(state: IdeUiState, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(scaled(36.dp))
            .background(palette.panel)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.tabs.forEach { tab ->
            val active = tab.id == state.activeTabId
            Row(
                Modifier
                    .height(scaled(36.dp))
                    .background(if (active) palette.tabActive else palette.tabInactive)
                    .clickable { actions.selectTab(tab.id) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    (if (tab.dirty) "● " else "") + tab.title,
                    color = if (active) palette.textPrimary else palette.textMuted,
                    fontSize = 12.sp,
                    fontStyle = if (tab.preview) FontStyle.Italic else FontStyle.Normal,
                )
                Box(
                    Modifier.size(scaled(20.dp)).clip(RoundedCornerShape(3.dp))
                        .clickable { actions.closeTab(tab.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Close ${tab.title}",
                        tint = palette.textMuted, modifier = Modifier.size(scaled(12.dp)),
                    )
                }
            }
            Spacer(Modifier.width(1.dp).height(scaled(36.dp)).background(palette.border))
        }
    }
}

// ---------- terminal ----------

@Composable
private fun TerminalPanel(state: IdeUiState, actions: IdeActions, modifier: Modifier) {
    val palette = LocalEditorPalette.current
    val context = LocalContext.current
    val service by TerminalHost.service.collectAsState()

    LaunchedEffect(Unit) { TerminalHost.connect(context) }

    val sandboxStatus by (service?.sandbox?.status
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow<SandboxInstaller.Status>(SandboxInstaller.Status.NotInstalled) })
        .collectAsState()
    val devToolsReady by (service?.sandbox?.devToolsReady
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(true) })
        .collectAsState()

    // Only a path the shell can actually use; an unreachable folder falls back
    // to the sandbox home rather than a directory that doesn't resolve.
    val cwd = state.workspaceUri?.let { WorkspaceRepository.accessiblePathOf(it) }
    val sessionIds by (service?.sessionIds
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<String>()) })
        .collectAsState()
    // One session per page: swiping the body moves between them, and the tab
    // strip drives the same pager, so the two can't disagree.
    val pager = rememberPagerState(pageCount = { sessionIds.size })
    val activeId = sessionIds.getOrNull(pager.currentPage)

    // The first terminal is created on demand. After that the panel is happy to
    // sit empty: an exited shell leaves its tab, and "+" makes a new one.
    var everOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(service, sandboxStatus is SandboxInstaller.Status.Installed) {
        if (service != null && !everOpened && sessionIds.isEmpty()) {
            everOpened = true
            TerminalHost.defaultSession(cwd)
        }
    }

    val session = remember(service, activeId, sessionIds, sandboxStatus is SandboxInstaller.Status.Installed) {
        activeId?.let { TerminalHost.session(it) }
    }
    val alive by (session?.alive
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(true) })
        .collectAsState()
    // The sandbox has no browser of its own: when something in there asks to
    // open a URL (gh auth login, npm login), it arrives here and goes to the
    // phone's browser — nothing to copy out by hand.
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(session) {
        session?.openUrl?.collect { url -> runCatching { uriHandler.openUri(url) } }
    }
    // Which sessions have exited, so their tabs can say so.
    val deadIds by produceState(initialValue = emptySet<String>(), sessionIds, service) {
        val live = sessionIds.mapNotNull { id -> TerminalHost.session(id)?.let { id to it.alive } }
        if (live.isEmpty()) {
            value = emptySet()
            return@produceState
        }
        combine(live.map { (id, flow) -> flow.map { alive -> id to alive } }) { pairs ->
            pairs.filterNot { it.second }.map { it.first }.toSet()
        }.collect { value = it }
    }

    var input by remember { mutableStateOf("") }
    val inputFocus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current

    val editMode = state.presets.editMode
    // The prompt is always typeable — reading mode only stops a tap on the
    // *output* from summoning the keyboard while you scroll.
    val typingAllowed = editMode || state.terminalMaximized
    val scheme = TerminalSchemes.resolve(state.presets.terminalScheme, palette)
    val termFont = state.presets.terminalFontSizeSp.sp
    val termLine = (state.presets.terminalFontSizeSp * 1.33f).sp

    Column(modifier.background(palette.chrome)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "  TERMINAL — shared across folders & windows",
                color = palette.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(6.dp),
            )
            Spacer(Modifier.weight(1f))
            ChromeIconButton(
                if (state.terminalMaximized) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                if (state.terminalMaximized) "Shrink terminal to a panel" else "Fill the page with the terminal",
                { actions.toggleTerminalMaximized() },
                baseIconSize = 15.dp,
            )
            ChromeIconButton(Icons.Filled.Close, "Close terminal", { actions.togglePanel() }, baseIconSize = 15.dp)
            Spacer(Modifier.width(4.dp))
            when (val s = sandboxStatus) {
                is SandboxInstaller.Status.NotInstalled -> Text(
                    "Install Linux",
                    color = palette.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { TerminalHost.installSandbox() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                is SandboxInstaller.Status.Installing -> Text(
                    s.step,
                    color = palette.warn, fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp),
                )
                is SandboxInstaller.Status.Failed -> Text(
                    "install failed: ${s.reason} — tap to retry",
                    color = palette.crit, fontSize = 11.sp, maxLines = 1,
                    modifier = Modifier
                        .clickable { TerminalHost.installSandbox() }
                        .padding(end = 8.dp),
                )
                is SandboxInstaller.Status.Installed -> if (devToolsReady) {
                    Text(
                        "alpine",
                        color = palette.good, fontSize = 11.sp,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                } else {
                    // A sandbox from an earlier build has the rootfs but none of
                    // the base tools — offer them rather than leaving `curl:
                    // not found` as the first thing that happens.
                    Text(
                        "Add dev tools",
                        color = palette.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { TerminalHost.installDevTools() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        val scope = rememberCoroutineScope()
        TerminalTabStrip(
            ids = sessionIds,
            activeIndex = pager.currentPage,
            deadIds = deadIds,
            onSelect = { i -> scope.launch { pager.animateScrollToPage(i) } },
            onClose = { TerminalHost.closeSession(it) },
            onNew = {
                val before = sessionIds.size
                TerminalHost.newSession(cwd)
                scope.launch { pager.animateScrollToPage(before) }
            },
        )
        if (activeId != null && !alive) {
            // The shell exited (ctrl-D or `exit`). Its tab stays so the output
            // is still readable: restart it, or close it and press + for a new
            // one — the same choices a browser tab gives you.
            Row(
                Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Shell exited.", color = palette.warn, fontSize = 12.sp)
                TextButton(onClick = { TerminalHost.restartSession(activeId, cwd) }) {
                    Text("Restart", fontSize = 12.sp)
                }
                TextButton(onClick = { TerminalHost.closeSession(activeId) }) {
                    Text("Close tab", fontSize = 12.sp)
                }
            }
        }
        if (sessionIds.isEmpty()) {
            // Every terminal closed. Not an error state — just an empty one,
            // with the button that fills it.
            Column(
                Modifier.weight(1f).fillMaxWidth().background(scheme.background),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No terminals open",
                    color = scheme.foreground.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = termFont,
                )
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { TerminalHost.newSession(cwd) }) {
                    Text("+  New terminal", fontSize = 13.sp)
                }
            }
        } else {
            // One page per session: swipe the body to move between them.
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                key = { i -> sessionIds.getOrElse(i) { i.toString() } },
            ) { page ->
                val pageSession = remember(sessionIds, page) {
                    sessionIds.getOrNull(page)?.let { TerminalHost.session(it) }
                }
                TerminalOutput(
                    session = pageSession,
                    scheme = scheme,
                    fontSize = termFont,
                    lineHeight = termLine,
                    hint = if (typingAllowed) "tap here and type a command below — the keyboard opens on tap"
                    else "reading mode — tap the prompt below to type",
                    onTap = {
                        // Same as tapping the code: working in here means the
                        // side panel can get out of the way.
                        if (state.sidebarVisible) actions.toggleSidebar()
                        if (typingAllowed) runCatching { inputFocus.requestFocus() }
                    },
                )
            }
        }
        // The soft keyboard has no Esc/Tab/arrows/Ctrl, and a shell is unusable
        // without them. Ctrl, Alt and Shift are sticky — tap, then tap a key —
        // the way a phone's shift key works, and clear after one use.
        var ctrl by remember { mutableStateOf(false) }
        var alt by remember { mutableStateOf(false) }
        var shift by remember { mutableStateOf(false) }

        fun send(data: String) {
            session?.write(data)
            ctrl = false
            alt = false
            shift = false
        }

        // Arrows go out in whichever form the program on the other end is
        // listening for (see TerminalKeys.cursorKey).
        fun sendCsi(final: Char) = send(
            TerminalKeys.cursorKey(
                final, shift, alt, ctrl,
                applicationMode = session?.applicationCursorKeys == true,
            ),
        )

        fun sendKey(ch: String) = send(TerminalKeys.key(ch, shift, alt, ctrl))

        Row(
            Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AccessoryKey("esc") { send("\u001B") }
                    ModifierKey("ctrl", ctrl) { ctrl = !ctrl }
                    ModifierKey("alt", alt) { alt = !alt }
                    ModifierKey("shift", shift) { shift = !shift }
                    AccessoryKey("tab") { if (shift) send("\u001B[Z") else sendKey("\t") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AccessoryKey("|") { sendKey("|") }
                    AccessoryKey("~") { sendKey("~") }
                    AccessoryKey("/") { sendKey("/") }
                    AccessoryKey("-") { sendKey("-") }
                    AccessoryKey("^C") { session?.sendInterrupt(); ctrl = false; alt = false; shift = false }
                    // Long-press selects and copies from the output above; this
                    // is the other half — the clipboard back into the shell.
                    AccessoryKey("paste") {
                        val text = clipboard.getText()?.text
                        if (!text.isNullOrEmpty()) session?.paste(text)
                        ctrl = false; alt = false; shift = false
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // Arrows in the inverted-T a physical keyboard uses: up centred
            // above down, left and right flanking it.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AccessoryKey("\u2191") { sendCsi('A') }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AccessoryKey("\u2190") { sendCsi('D') }
                    AccessoryKey("\u2193") { sendCsi('B') }
                    AccessoryKey("\u2192") { sendCsi('C') }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("$ ", color = scheme.ansi[10], fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            BasicTextField(
                value = input,
                // A sticky modifier has to catch the *soft keyboard* too: with
                // ctrl armed, typing c has to become ^C and go straight out,
                // not land in the input box as the letter c.
                onValueChange = { next ->
                    val typed = next.drop(input.length)
                    if ((ctrl || alt) && typed.length == 1) {
                        sendKey(typed)
                        input = ""
                    } else {
                        input = next
                    }
                },
                textStyle = TextStyle(
                    color = scheme.foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = termFont,
                ),
                cursorBrush = SolidColor(scheme.ansi[12]),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = {
                    val cmd = input
                    input = ""
                    session?.exec(cmd)
                }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(inputFocus)
                    // Typing at the prompt is the same signal as tapping the
                    // body: this is the pane being used now.
                    .onFocusChanged { if (it.isFocused && state.sidebarVisible) actions.toggleSidebar() },
            )
        }
    }
}

/**
 * Paint the emulator's spans with [scheme]: colours, bold/italic/underline and
 * reverse video (drawn by swapping the pair) — a coloured prompt, a `grep` hit
 * or a `git diff` block all rely on them. The cursor cell, when the program
 * has it visible, is drawn as a block the same way.
 */
private fun screenToAnnotated(
    screen: List<List<TerminalEmulator.Span>>,
    scheme: TerminalScheme,
    cursor: ShellSession.Cursor? = null,
): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    var cursorOffset = -1
    // Ranges of printed URLs, collected as the lines go by and turned into
    // links at the end: a build error or an auth prompt that prints a link
    // should be one tap away from the browser, not a copy-out-by-hand job.
    val links = ArrayList<Triple<Int, Int, String>>()
    screen.forEachIndexed { i, line ->
        if (i > 0) append('\n')
        val onThisLine = cursor != null && cursor.visible && cursor.row == i
        if (onThisLine) cursorOffset = length + cursor!!.col
        val lineStart = length
        URL_PATTERN.findAll(line.joinToString("") { it.text }).forEach { m ->
            links += Triple(lineStart + m.range.first, lineStart + m.range.last + 1, m.value)
        }
        line.forEach { span ->
            val st = span.style
            // Bold with one of the 8 base colours means "bright" on a real
            // terminal; keeping that makes ls/git output look right.
            val fgSlot = if (st.bold && st.fg in 0..7) st.fg + 8 else st.fg
            var fg = if (fgSlot >= 0) scheme.colorFor(fgSlot) else scheme.foreground
            var bg = if (st.bg >= 0) scheme.colorFor(st.bg) else Color.Unspecified
            if (st.reverse) {
                val swapped = if (bg == Color.Unspecified) scheme.background else bg
                bg = fg
                fg = swapped
            }
            if (st.dim) fg = fg.copy(alpha = 0.65f)
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = fg,
                    background = bg,
                    fontWeight = if (st.bold) FontWeight.Bold else null,
                    fontStyle = if (st.italic) FontStyle.Italic else null,
                    textDecoration = if (st.underline)
                        androidx.compose.ui.text.style.TextDecoration.Underline else null,
                ),
                length, length + span.text.length,
            )
            append(span.text)
        }
        // The cursor commonly sits one past the end of the line (at the prompt),
        // where there is no cell yet — pad out to it so the block can be drawn.
        if (onThisLine && cursorOffset >= length) append(" ".repeat(cursorOffset - length + 1))
    }
    links.forEach { (start, end, url) ->
        if (start >= length || end > length) return@forEach
        addLink(
            androidx.compose.ui.text.LinkAnnotation.Url(
                url,
                androidx.compose.ui.text.TextLinkStyles(
                    style = androidx.compose.ui.text.SpanStyle(
                        color = scheme.ansi[12],
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    ),
                ),
            ),
            start, end,
        )
    }
    if (cursorOffset in 0 until length) {
        addStyle(
            androidx.compose.ui.text.SpanStyle(
                color = scheme.background,
                background = scheme.foreground,
            ),
            cursorOffset, cursorOffset + 1,
        )
    }
}

/** http(s) URLs in terminal output, stopping before trailing punctuation. */
private val URL_PATTERN = Regex("""https?://[^\s"'<>`\\]*[^\s"'<>`\\.,;:!?)\]}]""")

// ---------- settings ----------

@Composable
private fun SettingsDialog(
    state: IdeUiState,
    actions: IdeActions,
    viewModel: IdeViewModel,
    onDismiss: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    val p = state.presets
    // The list keeps growing, so this is a page, not a dialog box.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(palette.surface)
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Row(
                Modifier.fillMaxWidth().background(palette.chrome).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Settings",
                    color = palette.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                ChromeIconButton(Icons.Filled.Close, "Close settings", onDismiss, baseIconSize = 20.dp)
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsSection("Theme")
                ThemePicker(state, actions)
                TextButton(onClick = { onDismiss(); actions.requestImportTheme() }) {
                    Text("Import theme…", fontSize = 13.sp)
                }

                SettingsSection("Reading")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Edit mode", color = palette.textPrimary, fontSize = 14.sp)
                        Text(
                            "Off = reading mode: editor is read-only and the keyboard\nstays down while you scroll.",
                            color = palette.textMuted, fontSize = 11.sp, lineHeight = 15.sp,
                        )
                    }
                    Switch(checked = p.editMode, onCheckedChange = { actions.toggleEditMode() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Word wrap", color = palette.textPrimary, fontSize = 14.sp)
                        Text(
                            "Wrap long lines to the screen instead of scrolling sideways.",
                            color = palette.textMuted, fontSize = 11.sp, lineHeight = 15.sp,
                        )
                    }
                    Switch(checked = p.wordWrap, onCheckedChange = { actions.setWordWrap(it) })
                }
                StepperRow("Editor font size", p.fontSizeSp) { actions.setEditorFontSize(it) }
                StepperRow("Terminal font size", p.terminalFontSizeSp) { actions.setTerminalFontSize(it) }

                SettingsSection("Terminal colours")
                TerminalSchemePicker(state.presets.terminalScheme) { actions.setTerminalScheme(it) }

                SettingsSection("Selection actions")
                Text(
                    "Highlight text in the editor and a small Actions button appears;\ntap it for find references, go to definition, copy and cut.\nLong-press in the editor opens the same menu with nothing\nselected, which is where Paste lives.",
                    color = palette.textMuted, fontSize = 11.sp, lineHeight = 15.sp,
                )
                StepperRow("Appears after (ms)", p.selectionDelayMs, step = 50) {
                    actions.setSelectionDelay(it)
                }

                SettingsSection("Touch targets")
                Text(
                    "How big the buttons and rows in the chrome are — the rail, panels,\ntabs and status bar. Larger is easier to hit on a small screen.",
                    color = palette.textMuted, fontSize = 11.sp, lineHeight = 15.sp,
                )
                TouchSizePicker(p.uiScale) { actions.setUiScale(it) }

                Text(
                    "All of these follow the open folder (.kodelab/workspace.json).",
                    color = palette.textMuted, fontSize = 11.sp,
                )

                SettingsSection("About")
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Kodelab", color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "An original product by Raka Aditya Soenarno",
                        color = palette.textPrimary, fontSize = 13.sp,
                    )
                    Text("@rakadityas", color = palette.accent, fontSize = 12.sp)
                }
                Text(
                    "Concept, requirements and design by Raka Aditya Soenarno.\n\n" +
                        "Apache-2.0. You're welcome to study this, build on it and take " +
                        "inspiration from it — keep the attribution when you do.",
                    color = palette.textMuted, fontSize = 11.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    val palette = LocalEditorPalette.current
    Text(
        title.uppercase(),
        color = palette.textMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

/** Built-in + imported themes, each as a swatch row that applies on tap. */
@Composable
private fun ThemePicker(state: IdeUiState, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    val builtIns = listOf(
        KodelabThemes.DARK to "Kodelab Dark",
        KodelabThemes.LIGHT to "Kodelab Light",
        KodelabThemes.SYSTEM to "Follow system",
    )
    val all = builtIns + state.customThemes.map { it.id to it.name }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        all.forEach { (id, name) ->
            val selected = state.presets.themeId == id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) palette.tabActive else Color.Transparent)
                    .clickable { actions.setTheme(id) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val swatch = when (id) {
                    KodelabThemes.DARK -> KodelabThemes.dark
                    KodelabThemes.LIGHT -> KodelabThemes.light
                    KodelabThemes.SYSTEM -> palette
                    else -> state.customThemes.firstOrNull { it.id == id }?.palette ?: palette
                }
                Row(
                    Modifier
                        .size(width = 34.dp, height = 16.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .border(1.dp, palette.border, RoundedCornerShape(3.dp)),
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(swatch.chrome))
                    Box(Modifier.weight(1f).fillMaxHeight().background(swatch.surface))
                    Box(Modifier.weight(1f).fillMaxHeight().background(swatch.accent))
                }
                Text(
                    name,
                    color = if (selected) palette.textPrimary else palette.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Filled.Check, contentDescription = "Selected",
                        tint = palette.accent, modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Terminal schemes as live previews — the swatch row is the actual palette. */
@Composable
private fun TerminalSchemePicker(current: String, onSet: (String) -> Unit) {
    val palette = LocalEditorPalette.current
    val options = listOf(TerminalSchemes.AUTO to "Follow theme") +
        TerminalSchemes.all.map { it.id to it.name }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (id, name) ->
            val selected = current == id
            val scheme = TerminalSchemes.resolve(id, palette)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) palette.tabActive else Color.Transparent)
                    .clickable { onSet(id) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(scheme.background)
                        .border(1.dp, palette.border, RoundedCornerShape(3.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // one chip per ANSI hue, in the order a prompt tends to use them
                    listOf(9, 10, 11, 12, 13, 14).forEach { slot ->
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.dp)).background(scheme.ansi[slot]))
                    }
                }
                Text(
                    name,
                    color = if (selected) palette.textPrimary else palette.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Filled.Check, contentDescription = "Selected",
                        tint = palette.accent, modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TouchSizePicker(current: Float, onSet: (Float) -> Unit) {
    val palette = LocalEditorPalette.current
    val options = listOf("Compact" to 1.0f, "Comfortable" to 1.25f, "Large" to 1.5f)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, value) ->
            val selected = kotlin.math.abs(current - value) < 0.01f
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) palette.accent else palette.panel)
                    .clickable { onSet(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else palette.textMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun StepperRow(label: String, value: Int, step: Int = 1, onSet: (Int) -> Unit) {
    val palette = LocalEditorPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        ChromeIconButton(Icons.Filled.Remove, "Decrease $label", { onSet(value - step) }, tint = palette.textPrimary)
        Text(
            "$value", color = palette.textPrimary, fontSize = 14.sp,
            modifier = Modifier.width(scaled(if (step > 1) 44.dp else 28.dp)),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        ChromeIconButton(Icons.Filled.Add, "Increase $label", { onSet(value + step) }, tint = palette.textPrimary)
    }
}

// ---------- command palette ----------

@Composable
private fun CommandPalette(state: IdeUiState, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = actions::closePalette) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.overlay)
                .padding(8.dp),
        ) {
            BasicTextField(
                value = state.paletteQuery,
                onValueChange = actions::paletteQueryChanged,
                textStyle = TextStyle(color = palette.textPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(palette.accent),
                singleLine = true,
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth().background(palette.surface, RoundedCornerShape(6.dp))
                            .padding(10.dp),
                    ) {
                        if (state.paletteQuery.isEmpty()) {
                            Text("Type a command or file name…", color = palette.textMuted, fontSize = 14.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.height(320.dp), state = rememberLazyListState()) {
                items(state.paletteItems, key = { it.id }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { actions.paletteItemPicked(item) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            when (item.kind) {
                                PaletteKind.COMMAND -> Icons.Filled.Terminal
                                PaletteKind.FILE -> Icons.Filled.Description
                                PaletteKind.THEME -> Icons.Filled.Palette
                            },
                            contentDescription = null,
                            tint = palette.accentMuted,
                            modifier = Modifier.size(15.dp),
                        )
                        Column {
                            Text(item.label, color = palette.textPrimary, fontSize = 13.sp)
                            item.detail?.let {
                                Text(it, color = palette.textMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

// ---------- status bar ----------

@Composable
private fun StatusBar(state: IdeUiState, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(scaled(26.dp))
            .background(palette.accent)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            Icons.Filled.Search, contentDescription = "Command palette",
            tint = Color.White, modifier = Modifier.size(14.dp).clickable { actions.openPalette() },
        )
        Text(
            state.workspaceName, color = Color.White, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.clickable { actions.openPalette() },
        )
        Text(state.statusText, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(
            state.presets.themeId,
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier.clickable { actions.cycleTheme() },
        )
    }
}
