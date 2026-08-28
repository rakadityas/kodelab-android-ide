package dev.kodelab.ide.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.kodelab.ide.editor.EditorWebView
import dev.kodelab.ide.git.GitFileStatus
import dev.kodelab.ide.terminal.SandboxInstaller
import dev.kodelab.ide.terminal.TerminalEmulator
import dev.kodelab.ide.terminal.TerminalHost
import dev.kodelab.ide.theme.EditorPalette
import dev.kodelab.ide.theme.LocalEditorPalette
import dev.kodelab.ide.workspace.WorkspaceRepository

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdeScaffold(state: IdeUiState, actions: IdeActions, viewModel: IdeViewModel) {
    val palette = LocalEditorPalette.current

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.surface)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ActivityRail(state, actions, viewModel)
            if (state.sidebarVisible) {
                SidePanel(state, actions, viewModel, Modifier.width(240.dp).fillMaxHeight())
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TabBar(state, actions)
                Box(Modifier.weight(1f).fillMaxWidth().background(palette.surface)) {
                    EditorWebView(
                        controller = viewModel.editor,
                        palette = palette,
                        onEvent = actions::onWebEvent,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (state.panelVisible) {
                    TerminalPanel(state, Modifier.height(230.dp).fillMaxWidth())
                }
            }
        }
        if (WindowInsets.isImeVisible && !state.panelVisible) {
            EditorAccessoryBar(actions)
        }
        StatusBar(state, actions)
    }

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
}

@Composable
private fun ActivityRail(state: IdeUiState, actions: IdeActions, viewModel: IdeViewModel) {
    val palette = LocalEditorPalette.current
    Column(
        Modifier.width(48.dp).fillMaxHeight().background(palette.chrome),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RailButton(Icons.Filled.FolderOpen, state.sidebarView == SidebarView.EXPLORER, "Explorer") {
            actions.setSidebarView(SidebarView.EXPLORER)
        }
        RailButton(Icons.Filled.Search, state.sidebarView == SidebarView.SEARCH, "Search") {
            actions.setSidebarView(SidebarView.SEARCH)
        }
        RailButton(Icons.Filled.Source, state.sidebarView == SidebarView.GIT, "Git") {
            actions.setSidebarView(SidebarView.GIT)
        }
        RailButton(Icons.Filled.Extension, state.sidebarView == SidebarView.EXTENSIONS, "Extensions") {
            actions.setSidebarView(SidebarView.EXTENSIONS)
        }
        Spacer(Modifier.weight(1f))
        RailButton(Icons.Filled.Save, false, "Save file") { actions.saveActiveTab() }
        RailButton(Icons.Filled.Terminal, state.panelVisible, "Terminal") { actions.togglePanel() }
        RailButton(Icons.AutoMirrored.Filled.OpenInNew, false, "New window") { viewModel.requestNewWindow() }
        RailButton(Icons.Filled.Palette, false, "Cycle theme") { actions.cycleTheme() }
    }
}

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val palette = LocalEditorPalette.current
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier.width(2.dp).fillMaxHeight().background(palette.accent)
                    .align(Alignment.CenterStart),
            )
        }
        IconButton(onClick = onClick) {
            Icon(
                icon, contentDescription = label,
                tint = if (active) palette.textPrimary else palette.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
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
                    IconButton(
                        onClick = { actions.requestFileOp(FileOpKind.NEW_FILE, null) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.NoteAdd, contentDescription = "New file",
                            tint = palette.textMuted, modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = { actions.requestFileOp(FileOpKind.NEW_FOLDER, null) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.CreateNewFolder, contentDescription = "New folder",
                            tint = palette.textMuted, modifier = Modifier.size(16.dp),
                        )
                    }
                }
                IconButton(onClick = { viewModel.requestOpenFolder() }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.FolderOpen, contentDescription = "Open folder",
                        tint = palette.textMuted, modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        when (state.sidebarView) {
            SidebarView.EXPLORER -> ExplorerTree(state, actions)
            SidebarView.SEARCH -> SearchPanel(state, actions)
            SidebarView.GIT -> GitPanel(state, actions)
            SidebarView.EXTENSIONS -> PanelPlaceholder(
                "Declarative add-ons (grammars, themes, snippets) arrive in v1.x.\n\n" +
                    "Kodelab never uses the Microsoft Marketplace; the catalogue will be " +
                    "open-source add-ons and, later, Open VSX.",
            )
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
                "This folder isn't on a path the sandbox can reach, so git can't run against it."
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

        // Commit box
        BasicTextField(
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
                        onOpen = { actions.openGitDiff(f, staged = true) },
                        onAction = { actions.gitUnstage(f) })
                }
            }
            if (unstaged.isNotEmpty()) {
                item(key = "hdr-changes") {
                    GitSectionHeader("Changes", unstaged.size, onStageAll = actions::gitStageAll)
                }
                items(unstaged, key = { "u:" + it.path }) { f ->
                    GitFileRow(f, staged = false, busy = git.busyPath == f.path,
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
        IconButton(onClick = actions::gitRefresh, modifier = Modifier.size(26.dp)) {
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
            IconButton(onClick = onStageAll, modifier = Modifier.size(20.dp)) {
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
        IconButton(onClick = onAction, enabled = !busy, modifier = Modifier.size(24.dp)) {
            Icon(
                if (staged) Icons.Filled.Remove else Icons.Filled.Add,
                contentDescription = if (staged) "Unstage" else "Stage",
                tint = palette.textMuted, modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun ExplorerTree(state: IdeUiState, actions: IdeActions) {
    val palette = LocalEditorPalette.current
    if (state.fileTree.isEmpty()) {
        PanelPlaceholder("No folder open.\nTap the folder icon above to pick one.")
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
                modifier = Modifier.size(14.dp),
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
            .height(38.dp)
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

@Composable
private fun AccessoryKey(label: String, onClick: () -> Unit) {
    val palette = LocalEditorPalette.current
    Box(
        Modifier
            .size(width = 40.dp, height = 30.dp)
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
            .height(36.dp)
            .background(palette.panel)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.tabs.forEach { tab ->
            val active = tab.id == state.activeTabId
            Row(
                Modifier
                    .height(36.dp)
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
                    Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
                        .clickable { actions.closeTab(tab.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Close ${tab.title}",
                        tint = palette.textMuted, modifier = Modifier.size(12.dp),
                    )
                }
            }
            Spacer(Modifier.width(1.dp).height(36.dp).background(palette.border))
        }
    }
}

// ---------- terminal ----------

@Composable
private fun TerminalPanel(state: IdeUiState, modifier: Modifier) {
    val palette = LocalEditorPalette.current
    val context = LocalContext.current
    val service by TerminalHost.service.collectAsState()

    LaunchedEffect(Unit) { TerminalHost.connect(context) }

    val sandboxStatus by (service?.sandbox?.status
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow<SandboxInstaller.Status>(SandboxInstaller.Status.NotInstalled) })
        .collectAsState()

    val cwd = state.workspaceUri?.let { WorkspaceRepository.localPathOf(it) }
    // re-resolve the session when the sandbox finishes installing (it restarts)
    val session = remember(service, sandboxStatus is SandboxInstaller.Status.Installed) {
        TerminalHost.defaultSession(cwd)
    }
    val screen by (session?.screen
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<List<TerminalEmulator.Span>>()) })
        .collectAsState()

    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(screen) { scroll.scrollTo(scroll.maxValue) }

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
                is SandboxInstaller.Status.Installed -> Text(
                    "alpine",
                    color = palette.good, fontSize = 11.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().background(palette.surface)
                .verticalScroll(scroll)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            if (screen.isEmpty()) {
                Text(
                    "connecting to terminal service…",
                    color = palette.textMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            }
            Text(
                text = remember(screen, palette.isDark) { screenToAnnotated(screen, palette) },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                softWrap = false,
            )
        }
        Row(
            Modifier.fillMaxWidth().background(palette.panel).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AccessoryKey("esc") { session?.write("\u001B") }
            AccessoryKey("⇥") { session?.write("\t") }
            AccessoryKey("^C") { session?.sendInterrupt() }
            Text("$ ", color = palette.accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(
                    color = palette.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(palette.accent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = {
                    val cmd = input
                    input = ""
                    session?.exec(cmd)
                }),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Standard 16-colour ANSI palette (indices 0..15), tuned for legibility. */
private val AnsiColors = intArrayOf(
    0xFF3B4048.toInt(), 0xFFE06C6C.toInt(), 0xFF6FB86F.toInt(), 0xFFD5A45C.toInt(),
    0xFF5C9FD6.toInt(), 0xFFB57EDC.toInt(), 0xFF4FB6C4.toInt(), 0xFFC5CDD3.toInt(),
    0xFF5A626C.toInt(), 0xFFF08787.toInt(), 0xFF8FD08F.toInt(), 0xFFE8C07A.toInt(),
    0xFF7FB8E8.toInt(), 0xFFCB9CE8.toInt(), 0xFF6FD4E0.toInt(), 0xFFF0F4F6.toInt(),
)

private fun ansiColor(index: Int): Color = when {
    index in 0..15 -> Color(AnsiColors[index])
    index in 16..231 -> { // 6x6x6 colour cube
        val n = index - 16
        val r = (n / 36) % 6; val g = (n / 6) % 6; val b = n % 6
        fun ch(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(ch(r), ch(g), ch(b))
    }
    index in 232..255 -> { val v = 8 + (index - 232) * 10; Color(v, v, v) }
    else -> Color.Unspecified
}

private fun screenToAnnotated(
    screen: List<List<TerminalEmulator.Span>>,
    palette: dev.kodelab.ide.theme.EditorPalette,
): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    screen.forEachIndexed { i, line ->
        if (i > 0) append('\n')
        line.forEach { span ->
            val fg = when {
                span.style.reverse && span.style.bg >= 0 -> ansiColor(span.style.bg)
                span.style.fg >= 0 -> ansiColor(span.style.fg)
                else -> palette.textPrimary
            }
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = fg,
                    fontWeight = if (span.style.bold) FontWeight.Bold else null,
                    textDecoration = if (span.style.underline)
                        androidx.compose.ui.text.style.TextDecoration.Underline else null,
                ),
                length, length + span.text.length,
            )
            append(span.text)
        }
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
            .height(26.dp)
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
