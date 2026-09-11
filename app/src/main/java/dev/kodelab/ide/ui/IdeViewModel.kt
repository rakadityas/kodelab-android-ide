package dev.kodelab.ide.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kodelab.ide.editor.EditorController
import dev.kodelab.ide.editor.Languages
import dev.kodelab.ide.ext.ExtensionAudit
import dev.kodelab.ide.ext.ExtensionManifest
import dev.kodelab.ide.ext.LspRecipe
import dev.kodelab.ide.ext.SnippetDef
import dev.kodelab.ide.git.GitFileStatus
import dev.kodelab.ide.git.GitService
import dev.kodelab.ide.git.SafGitReader
import dev.kodelab.ide.lsp.LspServerSupervisor
import dev.kodelab.ide.terminal.SandboxShell
import dev.kodelab.ide.terminal.TerminalHost
import dev.kodelab.ide.theme.KodelabThemes
import dev.kodelab.ide.theme.VsThemeImport
import dev.kodelab.ide.workspace.FileMatches
import dev.kodelab.ide.workspace.SearchMatch
import dev.kodelab.ide.workspace.SettingsStore
import dev.kodelab.ide.workspace.WorkspacePresets
import dev.kodelab.ide.workspace.SessionSnapshot
import dev.kodelab.ide.workspace.WorkspaceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** One-shot requests the Activity has to fulfil (system pickers, new windows). */
sealed interface IdeEvent {
    data object OpenFolderPicker : IdeEvent
    data object NewWindow : IdeEvent
    data object ImportThemeFile : IdeEvent
    /** A link tapped in the Markdown reader that points outside the workspace. */
    data class OpenUrl(val url: String) : IdeEvent
}

class IdeViewModel(
    private val settings: SettingsStore,
    private val repo: WorkspaceRepository,
) : ViewModel(), IdeActions {

    private val _state = MutableStateFlow(seedState())
    val state: StateFlow<IdeUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<IdeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<IdeEvent> = _events.asSharedFlow()

    /** Owned here so buffer pushes survive recomposition; the WebView reference
     *  inside is attached/detached by the composable. */
    val editor = EditorController()

    private var untitledCounter = 1
    /** Waiters for a tab's live text from the web layer (save, reader, …). All of
     *  a tab's waiters are served by the next `buffer.save` reply for that tab. */
    private val pendingText = mutableMapOf<String, MutableList<(String) -> Unit>>()

    init {
        viewModelScope.launch {
            val user = settings.settings.first()
            _state.update { s -> s.copy(presets = s.presets.withUserDefaults(user)) }
        }
    }

    /** No tabs and no side panel: the editor area shows the guide instead. */
    private fun seedState(): IdeUiState = IdeUiState(statusText = "Open a folder to begin")

    // ---------- workspace ----------

    override fun openFolder(treeUri: Uri) {
        viewModelScope.launch { openFolderNow(treeUri) }
    }

    private suspend fun openFolderNow(treeUri: Uri, revealSidebar: Boolean = true) {
        run {
            runCatching {
                runCatching { repo.persistPermission(treeUri) }
                val name = repo.workspaceName(treeUri)
                val presets = repo.loadPresets(treeUri).withUserDefaults(settings.settings.first())
                val roots = repo.listChildren(treeUri, null).map { e ->
                    // first load: every folder starts collapsed
                    FileNode(e.name, e.uri, e.docId, e.isDir, depth = 0, expanded = false)
                }
                val addons = loadAddons(treeUri)
                activeSnippets = addons.snippets
                activeLspRecipes = addons.lspRecipes
                _state.update {
                    it.copy(
                        workspaceUri = treeUri,
                        workspaceName = name,
                        presets = presets,
                        fileTree = roots,
                        customThemes = addons.customThemes,
                        extensions = addons.extensions,
                        sidebarVisible = it.sidebarVisible || revealSidebar,
                        sidebarView = SidebarView.EXPLORER,
                        statusText = "Opened $name",
                    )
                }
                editor.applySettings(presets)
                persistSession()
                // the shared terminal and the Git panel both follow the folder
                val host = WorkspaceRepository.accessiblePathOf(treeUri)
                TerminalHost.followWorkspace(host)
                gitRefresh()
                if (host == null) {
                    _state.update {
                        it.copy(statusText = "Opened $name — terminal and Git can't reach it (see Git panel)")
                    }
                }
            }.onFailure { e ->
                android.util.Log.e("Kodelab", "openFolder failed for $treeUri", e)
                _state.update {
                    it.copy(statusText = "Couldn't open folder: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    // ---------- session restore ----------

    /** Remember the open folder and file tabs, so a cold start can come back to them. */
    private fun persistSession() {
        val s = _state.value
        val snapshot = SessionSnapshot(
            workspaceUri = s.workspaceUri?.toString(),
            openFiles = s.tabs.mapNotNull { it.uri?.toString() },
            activeFile = s.tabs.firstOrNull { it.id == s.activeTabId }?.uri?.toString(),
        )
        viewModelScope.launch { runCatching { settings.saveSession(snapshot) } }
    }

    /**
     * Reopen the last folder and its file tabs. Called only for a cold start —
     * a window opened from inside the app starts empty on purpose.
     */
    fun restoreSession() {
        // Set synchronously, before setContent composes: otherwise the guide
        // renders for a frame and is then replaced, which reads as the app
        // tapping the folder button by itself.
        _state.update { it.copy(restoring = true) }
        viewModelScope.launch {
            val snap = runCatching { settings.session.first() }.getOrNull()
            val uriText = snap?.workspaceUri
            val treeUri = uriText?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (snap == null || treeUri == null) {
                _state.update { it.copy(restoring = false) }
                return@launch
            }
            if (!repo.canAccess(treeUri)) {
                _state.update {
                    it.copy(
                        restoring = false,
                        statusText = "Last folder is no longer available — open it again",
                    )
                }
                return@launch
            }
            // The side panel stays as it was: reopening a folder shouldn't slide
            // a panel open on its own.
            openFolderNow(treeUri, revealSidebar = false)
            var restored = 0
            snap.openFiles.forEach { fileText ->
                val uri = runCatching { Uri.parse(fileText) }.getOrNull() ?: return@forEach
                if (openDocumentNow(uri, displayNameOf(uri))) restored++
            }
            snap.activeFile?.let { active ->
                _state.value.tabs.firstOrNull { it.uri?.toString() == active }?.let { selectTab(it.id) }
            }
            _state.update {
                it.copy(
                    restoring = false,
                    statusText = if (restored > 0) "Restored ${it.workspaceName} — $restored file(s)"
                    else "Restored ${it.workspaceName}",
                )
            }
        }
    }

    /** File name for a uri we only have as text (a restored tab). */
    private fun displayNameOf(uri: Uri): String {
        val raw = if (uri.scheme == "file") uri.path.orEmpty()
        else runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: uri.lastPathSegment.orEmpty()
        return raw.substringAfterLast('/').substringAfterLast(':').ifBlank { "file" }
    }

    /** Collapse every expanded directory in the tree (keeps loaded children cached). */
    override fun collapseAll() {
        fun collapse(nodes: List<FileNode>): List<FileNode> = nodes.map { n ->
            if (n.children != null) n.copy(expanded = false, children = collapse(n.children)) else n
        }
        _state.update { it.copy(fileTree = collapse(it.fileTree)) }
    }

    // ---------- markdown reader (REQ 2) ----------

    /** Ask the web editor for a tab's current text and hand it to [onText]. */
    private fun requestBufferText(tabId: String, onText: (String) -> Unit) {
        pendingText.getOrPut(tabId) { mutableListOf() } += onText
        editor.requestSave(tabId)
    }

    override fun toggleTerminalMaximized() =
        _state.update { it.copy(terminalMaximized = !it.terminalMaximized) }

    override fun toggleReader() {
        val s = _state.value
        if (s.readerTabId != null) {
            _state.update { it.copy(readerTabId = null, readerText = "", statusText = "Editing") }
            return
        }
        val tab = s.tabs.firstOrNull { it.id == s.activeTabId }
        if (tab == null) {
            _state.update { it.copy(statusText = "Open a Markdown file to read it") }
            return
        }
        if (tab.languageId != "markdown") {
            _state.update { it.copy(statusText = "Reader supports Markdown files (.md)") }
            return
        }
        // Take the live buffer, so unsaved edits are rendered too.
        requestBufferText(tab.id) { text ->
            _state.update {
                if (it.activeTabId != tab.id) it
                else it.copy(readerTabId = tab.id, readerText = text, statusText = "Reading ${tab.title}")
            }
        }
    }

    /** Close the reader whenever the document under it is no longer the active one. */
    private fun closeReaderIfNotActive(activeId: String?) {
        if (_state.value.readerTabId != null && _state.value.readerTabId != activeId) {
            _state.update { it.copy(readerTabId = null, readerText = "") }
        }
    }

    override fun readerLinkClicked(href: String) {
        val target = href.substringBefore('#').trim()
        when {
            // in-document anchor: nothing to navigate to yet
            target.isEmpty() -> Unit
            Regex("^[a-zA-Z][\\w+.-]*:").containsMatchIn(target) ->
                _events.tryEmit(IdeEvent.OpenUrl(href))
            else -> {
                val name = target.trimStart('.', '/').substringAfterLast('/')
                val node = flattenFiles(_state.value.fileTree).firstOrNull { it.name == name }
                if (node != null) {
                    _state.update { it.copy(readerTabId = null, readerText = "") }
                    openFile(node)
                } else {
                    _state.update { it.copy(statusText = "Not in this folder: $target") }
                }
            }
        }
    }

    // ---------- reading controls (REQ: font size + edit/read mode) ----------

    /** Mutate the per-folder presets, push them to the editor, and persist. */
    private fun updatePresets(transform: (dev.kodelab.ide.workspace.WorkspacePresets) -> dev.kodelab.ide.workspace.WorkspacePresets) {
        val next = transform(_state.value.presets)
        _state.update { it.copy(presets = next) }
        editor.applySettings(next)
        viewModelScope.launch { _state.value.workspaceUri?.let { repo.savePresets(it, next) } }
    }

    override fun toggleEditMode() = updatePresets { it.copy(editMode = !it.editMode) }
    override fun setEditorFontSize(sp: Int) = updatePresets { it.copy(fontSizeSp = sp.coerceIn(8, 40)) }
    override fun setTerminalFontSize(sp: Int) = updatePresets { it.copy(terminalFontSizeSp = sp.coerceIn(8, 40)) }
    override fun setUiScale(scale: Float) = updatePresets { it.copy(uiScale = scale.coerceIn(1f, 2f)) }
    override fun setTerminalScheme(id: String) = updatePresets { it.copy(terminalScheme = id) }
    override fun setWordWrap(on: Boolean) = updatePresets { it.copy(wordWrap = on) }

    override fun toggleFullScreen() =
        _state.update { it.copy(fullScreen = !it.fullScreen) }

    override fun setSelectionDelay(ms: Int) =
        updatePresets { it.copy(selectionDelayMs = ms.coerceIn(0, 2000)) }

    override fun toggleDir(node: FileNode) {
        val tree = _state.value.workspaceUri ?: return
        if (node.expanded) {
            _state.update { it.copy(fileTree = updateNode(it.fileTree, node.docId) { n -> n.copy(expanded = false) }) }
            return
        }
        viewModelScope.launch {
            val children = node.children ?: repo.listChildren(tree, node.docId).map { e ->
                FileNode(e.name, e.uri, e.docId, e.isDir, depth = node.depth + 1)
            }
            _state.update {
                it.copy(fileTree = updateNode(it.fileTree, node.docId) { n ->
                    n.copy(expanded = true, children = children)
                })
            }
        }
    }

    private fun updateNode(
        nodes: List<FileNode>,
        docId: String,
        transform: (FileNode) -> FileNode,
    ): List<FileNode> = nodes.map { n ->
        when {
            n.docId == docId -> transform(n)
            n.children != null -> n.copy(children = updateNode(n.children, docId, transform))
            else -> n
        }
    }

    // ---------- files & tabs ----------

    override fun openFile(node: FileNode) {
        if (node.isDir) { toggleDir(node); return }
        openDocument(node.uri, node.name)
    }

    /** Open [uri] into a tab (reusing an existing one), optionally jumping to [revealLine]. */
    private fun openDocument(uri: Uri, name: String, revealLine: Int? = null) {
        viewModelScope.launch { openDocumentNow(uri, name, revealLine) }
    }

    /** The body of [openDocument], awaitable so a session restore keeps tab order. */
    private suspend fun openDocumentNow(uri: Uri, name: String, revealLine: Int? = null): Boolean {
        val existing = _state.value.tabs.firstOrNull { it.uri == uri }
        if (existing != null) {
            selectTab(existing.id)
            revealLine?.let { editor.revealLine(existing.id, it) }
            return true
        }
        val text = repo.readText(uri)
        if (text == null) {
            android.util.Log.w("Kodelab", "readText returned null for $uri")
            _state.update { it.copy(statusText = "Can't read $name") }
            return false
        }
        val lang = Languages.forFileName(name)
        val tab = EditorTab(UUID.randomUUID().toString(), name, uri, lang)
        _state.update { s ->
            // a new open replaces the current preview tab, VS-style
            val tabs = s.tabs.filterNot { it.preview && !it.dirty } + tab.copy(preview = false)
            s.copy(tabs = tabs, activeTabId = tab.id, statusText = name, readerTabId = null, readerText = "")
        }
        editor.openBuffer(tab.id, text, lang)
        editor.showBuffer(tab.id)
        revealLine?.let { editor.revealLine(tab.id, it) }
        persistSession()
        return true
    }

    override fun selectTab(id: String) {
        _state.update { it.copy(activeTabId = id) }
        closeReaderIfNotActive(id)
        editor.showBuffer(id)
        persistSession()
    }

    override fun closeTab(id: String) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        if (tab.dirty) {
            _state.update { it.copy(pendingCloseTabId = id) }
            return
        }
        reallyClose(id)
    }

    override fun confirmCloseDiscard() {
        _state.value.pendingCloseTabId?.let { reallyClose(it) }
    }

    override fun cancelClose() = _state.update { it.copy(pendingCloseTabId = null) }

    private fun reallyClose(id: String) {
        editor.closeBuffer(id)
        pendingText.remove(id)
        _state.update { s ->
            val remaining = s.tabs.filterNot { it.id == id }
            s.copy(
                tabs = remaining,
                pendingCloseTabId = null,
                activeTabId = when {
                    s.activeTabId != id -> s.activeTabId
                    remaining.isEmpty() -> null
                    else -> remaining.last().id
                },
            )
        }
        _state.value.activeTabId?.let { editor.showBuffer(it) }
        persistSession()
    }

    override fun newUntitled() {
        val tab = EditorTab(
            UUID.randomUUID().toString(), "Untitled-${untitledCounter++}", null, "plaintext",
        )
        _state.update { s -> s.copy(tabs = s.tabs + tab, activeTabId = tab.id) }
        editor.openBuffer(tab.id, "", "plaintext")
    }

    override fun saveActiveTab() {
        val tab = _state.value.tabs.firstOrNull { it.id == _state.value.activeTabId } ?: return
        requestBufferText(tab.id) { text -> persistTab(tab, text) }
    }

    private fun persistTab(tab: EditorTab, text: String) {
        viewModelScope.launch {
            val uri = tab.uri
            if (uri == null) {
                _state.update { it.copy(statusText = "Untitled buffers can't be saved yet — open a file from a folder") }
                return@launch
            }
            val ok = repo.writeText(uri, text)
            _state.update { s ->
                s.copy(
                    statusText = if (ok) "Saved ${tab.title}" else "Save failed: ${tab.title}",
                    tabs = if (ok) s.tabs.map { if (it.id == tab.id) it.copy(dirty = false) else it } else s.tabs,
                )
            }
            if (ok) editor.markSaved(tab.id)
        }
    }

    // ---------- Explorer file operations ----------

    override fun requestFileOp(kind: FileOpKind, target: FileNode?) {
        val initial = if (kind == FileOpKind.RENAME) target?.name.orEmpty() else ""
        _state.update { it.copy(pendingFileOp = FileOpRequest(kind, target, initial)) }
    }

    override fun cancelFileOp() = _state.update { it.copy(pendingFileOp = null) }

    override fun confirmFileOp(name: String) {
        val op = _state.value.pendingFileOp ?: return
        val tree = _state.value.workspaceUri ?: return
        _state.update { it.copy(pendingFileOp = null) }
        viewModelScope.launch {
            val parentUri = when {
                op.kind == FileOpKind.RENAME || op.kind == FileOpKind.DELETE -> null
                op.target == null -> android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
                )
                else -> op.target.uri
            }
            val ok = when (op.kind) {
                FileOpKind.NEW_FILE -> repo.createFile(parentUri!!, name) != null
                FileOpKind.NEW_FOLDER -> repo.createDirectory(parentUri!!, name) != null
                FileOpKind.RENAME -> {
                    val newUri = repo.rename(op.target!!.uri, name)
                    if (newUri != null) {
                        _state.update { s ->
                            s.copy(tabs = s.tabs.map { t ->
                                if (t.uri == op.target.uri) t.copy(title = name, uri = newUri) else t
                            })
                        }
                    }
                    newUri != null
                }
                FileOpKind.DELETE -> {
                    val ok = repo.delete(op.target!!.uri)
                    if (ok) {
                        _state.value.tabs.firstOrNull { it.uri == op.target.uri }
                            ?.let { reallyClose(it.id) }
                    }
                    ok
                }
            }
            val dirToRefresh = when (op.kind) {
                FileOpKind.NEW_FILE, FileOpKind.NEW_FOLDER -> op.target // null == root
                FileOpKind.RENAME, FileOpKind.DELETE ->
                    op.target?.let { findParentDir(_state.value.fileTree, it.docId) }
            }
            refreshDir(dirToRefresh)
            _state.update {
                it.copy(statusText = if (ok) "${op.kind.name.lowercase().replace('_', ' ')}: $name" else "Operation failed")
            }
        }
    }

    /** The directory node whose loaded children contain [docId], or null (root). */
    private fun findParentDir(nodes: List<FileNode>, docId: String): FileNode? {
        for (n in nodes) {
            val children = n.children ?: continue
            if (children.any { it.docId == docId }) return n
            findParentDir(children, docId)?.let { return it }
        }
        return null
    }

    /** Re-list the given directory (or the workspace root). */
    private suspend fun refreshDir(dir: FileNode?) {
        val tree = _state.value.workspaceUri ?: return
        if (dir == null) {
            val roots = repo.listChildren(tree, null).map { e ->
                FileNode(e.name, e.uri, e.docId, e.isDir, depth = 0)
            }
            // keep expanded state for dirs that still exist
            val old = _state.value.fileTree.associateBy { it.docId }
            _state.update { s ->
                s.copy(fileTree = roots.map { n -> old[n.docId]?.let { n.copy(expanded = it.expanded, children = it.children) } ?: n })
            }
        } else {
            val children = repo.listChildren(tree, dir.docId).map { e ->
                FileNode(e.name, e.uri, e.docId, e.isDir, depth = dir.depth + 1)
            }
            _state.update {
                it.copy(fileTree = updateNode(it.fileTree, dir.docId) { n ->
                    n.copy(expanded = true, children = children)
                })
            }
        }
    }

    // ---------- editor accessory keys ----------

    override fun sendEditorCommand(command: String) =
        editor.send("input.exec", org.json.JSONObject().put("command", command))

    override fun sendEditorText(text: String) =
        editor.send("input.type", org.json.JSONObject().put("text", text))

    // ---------- chrome ----------

    override fun toggleSidebar() = _state.update { it.copy(sidebarVisible = !it.sidebarVisible) }
    override fun togglePanel() = _state.update { it.copy(panelVisible = !it.panelVisible) }
    override fun setSidebarView(view: SidebarView) {
        _state.update { it.copy(sidebarView = view, sidebarVisible = true) }
        if (view == SidebarView.GIT) gitRefresh()
    }

    // ---------- search across files (REQ 1) ----------

    private var searchJob: Job? = null

    override fun searchQueryChanged(query: String) {
        _state.update { it.copy(searchQuery = query) }
        // debounce typing; a blank query just clears results
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false, searchSummary = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            performSearch(query)
        }
    }

    override fun runSearch() {
        val q = _state.value.searchQuery
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { performSearch(q) }
    }

    /**
     * Look up [symbol] across the workspace. [definitionsOnly] jumps straight to
     * the single most likely declaration when there is exactly one, and
     * otherwise falls back to showing every hit — a heuristic, not a compiler,
     * so it says so in the status line rather than pretending to be precise.
     */
    private fun symbolSearch(symbol: String, definitionsOnly: Boolean) {
        val q = symbol.trim()
        if (q.isBlank()) {
            _state.update { it.copy(statusText = "Select a symbol first") }
            return
        }
        if (_state.value.workspaceUri == null) {
            _state.update { it.copy(statusText = "Open a folder to search for \"$q\"") }
            return
        }
        searchJob?.cancel()
        _state.update {
            it.copy(
                searchQuery = q,
                sidebarView = SidebarView.SEARCH,
                sidebarVisible = true,
                statusText = if (definitionsOnly) "Looking for where $q is defined…" else "Finding references to $q…",
            )
        }
        searchJob = viewModelScope.launch {
            performSearch(q)
            val results = _state.value.searchResults
            if (!definitionsOnly) return@launch
            val declarations = results.flatMap { f -> f.matches.map { f to it } }
                .filter { (_, m) -> looksLikeDeclaration(m.text, q) }
            when (declarations.size) {
                0 -> _state.update {
                    it.copy(statusText = "No declaration of $q found — showing all ${results.sumOf { f -> f.matches.size }} hit(s)")
                }
                else -> {
                    val (file, match) = declarations.first()
                    openDocument(file.uri, file.name, revealLine = match.line)
                    _state.update {
                        it.copy(
                            statusText = if (declarations.size == 1) "Definition of $q"
                            else "${declarations.size} candidates for $q — showing the first",
                        )
                    }
                }
            }
        }
    }

    /** Does this line read like "here is where [symbol] is introduced"? */
    private fun looksLikeDeclaration(line: String, symbol: String): Boolean {
        val keywords = listOf(
            "fun", "class", "object", "interface", "val", "var", "def", "function",
            "const", "let", "struct", "enum", "type", "trait", "impl", "package",
            "public", "private", "protected", "static", "data class",
        )
        val before = line.substringBefore(symbol).trim()
        if (before.isEmpty()) return false
        val lastWord = before.split(' ', '\t', '(', '<').lastOrNull { it.isNotBlank() } ?: return false
        return keywords.any { it.equals(lastWord, ignoreCase = false) }
    }

    private suspend fun performSearch(query: String) {
        val tree = _state.value.workspaceUri ?: run {
            _state.update { it.copy(searchSummary = "Open a folder to search") }
            return
        }
        _state.update { it.copy(searching = true) }
        val results = repo.search(tree, query)
        val hits = results.sumOf { it.matches.size }
        val summary = when {
            hits == 0 -> "No results"
            else -> "$hits ${plural(hits, "result")} in ${results.size} ${plural(results.size, "file")}"
        }
        _state.update { it.copy(searchResults = results, searching = false, searchSummary = summary) }
    }

    override fun openSearchHit(file: FileMatches, match: SearchMatch) =
        openDocument(file.uri, file.name, revealLine = match.line)

    private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"

    // ---------- git panel (REQ 5: git over the sandbox CLI) ----------

    /** Stateless; rebuilt each use so it always sees the current sandbox binding. */
    private fun gitService(): GitService? =
        TerminalHost.service.value?.sandbox?.let { GitService(SandboxShell(it)) }

    private fun hostPath(): String? =
        _state.value.workspaceUri?.let { WorkspaceRepository.accessiblePathOf(it) }

    override fun gitRefresh() {
        val tree = _state.value.workspaceUri
        val host = hostPath()
        val svc = gitService()
        _state.update { it.copy(git = it.git.copy(loading = true)) }
        viewModelScope.launch {
            // A folder the sandbox can't reach still has a readable .git, so
            // read that directly rather than reporting nothing at all.
            if (host == null && tree != null) {
                when (val r = SafGitReader(repo).read(tree)) {
                    is SafGitReader.Result.Ready -> {
                        _state.update {
                            it.copy(
                                git = GitUiState(
                                    availability = GitAvailability.READY,
                                    status = r.status,
                                    readOnly = true,
                                    commitMessage = it.git.commitMessage,
                                ),
                            )
                        }
                        return@launch
                    }
                    is SafGitReader.Result.NotARepo -> {
                        _state.update {
                            it.copy(git = GitUiState(availability = GitAvailability.NOT_A_REPO))
                        }
                        return@launch
                    }
                    is SafGitReader.Result.Unsupported -> {
                        _state.update {
                            it.copy(
                                git = GitUiState(
                                    availability = GitAvailability.ERROR,
                                    message = r.reason,
                                ),
                            )
                        }
                        return@launch
                    }
                }
            }
            if (svc == null) {
                _state.update {
                    it.copy(git = it.git.copy(availability = GitAvailability.SANDBOX_MISSING, loading = false))
                }
                return@launch
            }
            val result = svc.refresh(host)
            _state.update { it.copy(git = it.git.applyRepoState(result).copy(readOnly = false)) }
        }
    }

    private fun GitUiState.applyRepoState(state: GitService.RepoState): GitUiState = when (state) {
        GitService.RepoState.SandboxMissing ->
            copy(availability = GitAvailability.SANDBOX_MISSING, status = null, message = null, loading = false)
        GitService.RepoState.GitMissing ->
            copy(availability = GitAvailability.GIT_MISSING, status = null, message = null, loading = false)
        GitService.RepoState.NotARepo ->
            copy(availability = GitAvailability.NOT_A_REPO, status = null, message = null, loading = false)
        GitService.RepoState.NoPath ->
            copy(availability = GitAvailability.NO_PATH, status = null, message = null, loading = false)
        is GitService.RepoState.Ready ->
            copy(availability = GitAvailability.READY, status = state.status, message = null, loading = false, busyPath = null)
        is GitService.RepoState.Error ->
            copy(availability = GitAvailability.ERROR, message = state.message, loading = false)
    }

    /** Run a per-file git op, then refresh; marks the row busy while in flight. */
    private fun gitFileOp(path: String, op: suspend (GitService, String) -> SandboxShell.Result) {
        val svc = gitService() ?: return
        val host = hostPath() ?: return
        _state.update { it.copy(git = it.git.copy(busyPath = path)) }
        viewModelScope.launch {
            val r = op(svc, host)
            if (!r.ok) {
                _state.update { it.copy(git = it.git.copy(message = r.message(), busyPath = null), statusText = "git: ${r.message()}") }
                return@launch
            }
            val refreshed = svc.refresh(host)
            _state.update { it.copy(git = it.git.applyRepoState(refreshed)) }
        }
    }

    override fun gitStage(file: GitFileStatus) =
        gitFileOp(file.path) { s, h -> s.stage(h, file.path) }

    override fun gitUnstage(file: GitFileStatus) =
        gitFileOp(file.path) { s, h -> s.unstage(h, file.path) }

    override fun gitStageAll() {
        val svc = gitService() ?: return
        val host = hostPath() ?: return
        _state.update { it.copy(git = it.git.copy(loading = true)) }
        viewModelScope.launch {
            svc.stageAll(host)
            _state.update { it.copy(git = it.git.applyRepoState(svc.refresh(host))) }
        }
    }

    override fun gitCommitMessageChanged(message: String) =
        _state.update { it.copy(git = it.git.copy(commitMessage = message)) }

    override fun gitCommit() {
        val svc = gitService() ?: return
        val host = hostPath() ?: return
        val msg = _state.value.git.commitMessage.trim()
        if (msg.isEmpty()) {
            _state.update { it.copy(git = it.git.copy(message = "Enter a commit message")) }
            return
        }
        _state.update { it.copy(git = it.git.copy(loading = true)) }
        viewModelScope.launch {
            val r = svc.commit(host, msg)
            if (!r.ok) {
                _state.update { it.copy(git = it.git.copy(message = r.message(), loading = false), statusText = "git commit: ${r.message()}") }
                return@launch
            }
            _state.update {
                it.copy(
                    git = it.git.copy(commitMessage = "").applyRepoState(svc.refresh(host)),
                    statusText = "Committed",
                )
            }
        }
    }

    override fun openGitDiff(file: GitFileStatus, staged: Boolean) {
        val svc = gitService() ?: return
        val host = hostPath() ?: return
        viewModelScope.launch {
            val r = svc.diff(host, file.path, staged)
            val text = when {
                r.stdout.isNotBlank() -> r.stdout
                !r.ok -> r.message()
                else -> "No changes to show for ${file.path}."
            }
            val title = (if (staged) "Δ staged " else "Δ ") + file.path.substringAfterLast('/')
            openVirtualBuffer("diff:${file.path}:$staged", title, text, "diff")
        }
    }

    /** Open (or replace) a read-only virtual buffer, e.g. a diff, in a tab. */
    private fun openVirtualBuffer(key: String, title: String, text: String, lang: String) {
        val existing = _state.value.tabs.firstOrNull { it.id == key }
        val tab = existing ?: EditorTab(key, title, uri = null, languageId = lang)
        _state.update { s ->
            val tabs = if (existing != null) s.tabs else s.tabs.filterNot { it.preview && !it.dirty } + tab
            s.copy(tabs = tabs, activeTabId = tab.id, statusText = title, readerTabId = null, readerText = "")
        }
        editor.openBuffer(tab.id, text, lang)
        editor.showBuffer(tab.id)
    }

    // ---------- theme & presets (REQ 2 / REQ 8) ----------

    override fun setTheme(themeId: String) {
        val next = _state.value.presets.copy(themeId = themeId)
        _state.update { it.copy(presets = next) }
        viewModelScope.launch {
            settings.setTheme(themeId)
            // per-folder preset: the theme follows the workspace, not the device
            _state.value.workspaceUri?.let { repo.savePresets(it, next) }
        }
    }

    override fun cycleTheme() {
        // Cycle built-ins then any imported themes, in a stable order.
        val order = listOf(KodelabThemes.DARK, KodelabThemes.LIGHT, KodelabThemes.SYSTEM) +
            _state.value.customThemes.map { it.id }
        val idx = order.indexOf(_state.value.presets.themeId)
        setTheme(order[(idx + 1) % order.size])
    }

    /** Palettes for the composable layer, keyed by imported-theme id. */
    fun customPalettes(): Map<String, dev.kodelab.ide.theme.EditorPalette> =
        _state.value.customThemes.associate { it.id to it.palette }

    private suspend fun loadCustomThemes(treeUri: android.net.Uri): List<CustomTheme> =
        repo.listThemeFiles(treeUri).mapNotNull { (_, json) ->
            runCatching { VsThemeImport.parse(json) }.getOrNull()?.let {
                CustomTheme(it.id, it.name, it.palette)
            }
        }.distinctBy { it.id }

    // ---------- declarative extensions (REQ 4) ----------

    /** Snippets contributed by activated (audit-passed) extensions in this workspace. */
    private var activeSnippets: List<SnippetDef> = emptyList()
    /** LSP recipes from activated extensions, one per languageId (last wins). */
    private var activeLspRecipes: Map<String, LspRecipe> = emptyMap()

    private data class Addons(
        val customThemes: List<CustomTheme>,
        val extensions: List<LoadedExtension>,
        val snippets: List<SnippetDef>,
        val lspRecipes: Map<String, LspRecipe>,
    )

    /** Load imported themes + declarative extensions; audit each and activate only permissive ones. */
    private suspend fun loadAddons(treeUri: android.net.Uri): Addons {
        val fileThemes = loadCustomThemes(treeUri)
        val loaded = mutableListOf<LoadedExtension>()
        val extThemes = mutableListOf<CustomTheme>()
        val snippets = mutableListOf<SnippetDef>()
        val recipes = LinkedHashMap<String, LspRecipe>()

        for (raw in repo.listExtensions(treeUri)) {
            val manifest = runCatching { ExtensionManifest.parse(raw.manifestJson) }.getOrElse { err ->
                loaded += LoadedExtension(
                    id = raw.dirName, name = raw.dirName, version = "?", publisher = null,
                    license = null, description = null, allowed = false,
                    summary = "unreadable manifest", issues = listOf(err.message ?: "invalid manifest"),
                )
                return@getOrElse null
            } ?: continue

            val audit = ExtensionAudit.audit(manifest)
            loaded += LoadedExtension(
                id = manifest.id, name = manifest.name, version = manifest.version,
                publisher = manifest.publisher, license = manifest.license, description = manifest.description,
                allowed = audit.allowed, summary = summarize(manifest),
                issues = audit.issues.map { "${it.subject}: ${it.reason}" },
            )
            if (!audit.allowed) continue

            snippets += manifest.snippets
            manifest.lspRecipes.forEach { recipes[it.languageId] = it }
            for (t in manifest.themes) {
                val json = raw.files[t.file] ?: continue
                runCatching { VsThemeImport.parse(json, fallbackName = t.label) }.getOrNull()?.let { p ->
                    extThemes += CustomTheme(
                        id = "ext-${manifest.id}-${p.id}",
                        name = "${t.label} · ${manifest.name}",
                        palette = p.palette,
                    )
                }
            }
        }
        val allThemes = (fileThemes + extThemes).distinctBy { it.id }.sortedBy { it.name.lowercase() }
        return Addons(allThemes, loaded, snippets, recipes)
    }

    private fun summarize(m: ExtensionManifest): String {
        val parts = buildList {
            if (m.themes.isNotEmpty()) add("${m.themes.size} ${plural(m.themes.size, "theme")}")
            if (m.snippets.isNotEmpty()) add("${m.snippets.size} ${plural(m.snippets.size, "snippet")}")
            if (m.grammars.isNotEmpty()) add("${m.grammars.size} ${plural(m.grammars.size, "grammar")}")
            if (m.lspRecipes.isNotEmpty()) add("${m.lspRecipes.size} LSP")
        }
        return if (parts.isEmpty()) "no contributions" else parts.joinToString(" · ")
    }

    // ---------- language servers (REQ 3: LSP over the sandbox) ----------

    private var supervisor: LspServerSupervisor? = null
    /** LSP `file://` uri -> open tab id, so diagnostics land on the right buffer. */
    private val lspUriToTab = HashMap<String, String>()

    private fun ensureSupervisor(): LspServerSupervisor? {
        supervisor?.let { return it }
        val sb = TerminalHost.service.value?.sandbox ?: return null
        val s = LspServerSupervisor(sb)
        supervisor = s
        viewModelScope.launch {
            s.diagnostics.collect { pub ->
                lspUriToTab[pub.uri]?.let { tabId -> editor.pushDiagnostics(tabId, pub.diagnostics) }
            }
        }
        viewModelScope.launch {
            s.statuses.collect { statuses ->
                statuses.values.maxByOrNull { it.state.ordinal }?.let { st ->
                    val suffix = st.message?.let { " — $it" } ?: ""
                    _state.update { it.copy(statusText = "LSP ${st.languageId}: ${st.state.name.lowercase()}$suffix") }
                }
            }
        }
        return s
    }

    /** True when the active file's language has a matching LSP recipe (drives the palette entry). */
    private fun activeRecipe(): LspRecipe? {
        val tab = _state.value.tabs.firstOrNull { it.id == _state.value.activeTabId } ?: return null
        return activeLspRecipes[tab.languageId]
    }

    override fun startLanguageServer() {
        val tab = _state.value.tabs.firstOrNull { it.id == _state.value.activeTabId } ?: return
        val recipe = activeLspRecipes[tab.languageId] ?: run {
            _state.update { it.copy(statusText = "No language-server recipe for ${tab.languageId}") }
            return
        }
        val sup = ensureSupervisor() ?: run {
            _state.update { it.copy(statusText = "Install the Linux sandbox first (terminal → Install Linux)") }
            return
        }
        if (!sup.installed) {
            _state.update { it.copy(statusText = "Install the Linux sandbox first, then apk add the server") }
            return
        }
        val workspaceHost = _state.value.workspaceUri?.let { WorkspaceRepository.localPathOf(it) }
        if (!sup.start(recipe, workspaceHost)) return

        val uri = tab.uri ?: return
        val hostPath = WorkspaceRepository.localPathOfDocument(uri) ?: return
        val lspUri = "file://$hostPath"
        viewModelScope.launch {
            val text = repo.readText(uri) ?: return@launch
            val client = sup.client(tab.languageId) ?: return@launch
            client.initialized.first { it } // wait for the handshake
            lspUriToTab[lspUri] = tab.id
            client.didOpen(lspUri, tab.languageId, 1, text)
        }
    }

    override fun onCleared() {
        supervisor?.stopAll()
        super.onCleared()
    }

    override fun requestImportTheme() { _events.tryEmit(IdeEvent.ImportThemeFile) }

    override fun importThemeFrom(uri: Uri) {
        viewModelScope.launch {
            val json = repo.readText(uri)
            if (json == null) {
                _state.update { it.copy(statusText = "Couldn't read that theme file") }
                return@launch
            }
            val parsed = runCatching { VsThemeImport.parse(json) }.getOrElse { err ->
                val reason = err.message ?: "invalid"
                _state.update { it.copy(statusText = "Not a theme JSON: $reason") }
                return@launch
            }
            val theme = CustomTheme(parsed.id, parsed.name, parsed.palette)
            // Persist into the workspace so it reappears next time (REQ 8).
            _state.value.workspaceUri?.let { repo.saveThemeFile(it, "${parsed.id}.json", json) }
            _state.update { s ->
                val merged = (s.customThemes.filterNot { it.id == theme.id } + theme)
                    .sortedBy { it.name.lowercase() }
                s.copy(customThemes = merged, statusText = "Imported theme “${parsed.name}”")
            }
            setTheme(parsed.id)
        }
    }

    // ---------- command palette ----------

    override fun openPalette() {
        _state.update { it.copy(paletteOpen = true, paletteQuery = "", paletteItems = buildPalette("")) }
    }

    override fun closePalette() = _state.update { it.copy(paletteOpen = false) }

    override fun paletteQueryChanged(query: String) =
        _state.update { it.copy(paletteQuery = query, paletteItems = buildPalette(query)) }

    private fun buildPalette(query: String): List<PaletteItem> {
        val commands = listOf(
            PaletteItem("cmd.save", "Save file", "writes the active tab to disk", PaletteKind.COMMAND),
            PaletteItem("cmd.newFile", "New untitled file", null, PaletteKind.COMMAND),
            PaletteItem("cmd.openFolder", "Open folder…", "pick a workspace with the system picker", PaletteKind.COMMAND),
            PaletteItem("cmd.openSandboxHome", "Open Alpine home", "browse a repo cloned in the terminal", PaletteKind.COMMAND),
            PaletteItem("cmd.newWindow", "New window", "another Kodelab window, same terminal", PaletteKind.COMMAND),
            PaletteItem("cmd.findRefs", "Find references for selection", "search the workspace for the selected symbol", PaletteKind.COMMAND),
            PaletteItem("cmd.goToDef", "Go to definition (workspace)", "jump to where the selected symbol is declared", PaletteKind.COMMAND),
            PaletteItem("cmd.reader", "Toggle Markdown reader", "render the open .md as a document", PaletteKind.COMMAND),
            PaletteItem("cmd.toggleTerminal", "Toggle terminal panel", null, PaletteKind.COMMAND),
            PaletteItem("cmd.toggleSidebar", "Toggle side panel", null, PaletteKind.COMMAND),
            PaletteItem("theme.${KodelabThemes.DARK}", "Theme: Kodelab Dark", null, PaletteKind.THEME),
            PaletteItem("theme.${KodelabThemes.LIGHT}", "Theme: Kodelab Light", null, PaletteKind.THEME),
            PaletteItem("theme.${KodelabThemes.SYSTEM}", "Theme: follow system", null, PaletteKind.THEME),
            PaletteItem("cmd.importTheme", "Import theme…", "load a standard color-theme JSON", PaletteKind.COMMAND),
        )
        val customThemes = _state.value.customThemes.map { t ->
            PaletteItem("theme.${t.id}", "Theme: ${t.name}", "imported", PaletteKind.THEME)
        }
        val snippets = activeSnippets.mapIndexed { i, s ->
            PaletteItem("snippet.$i", "Snippet: ${s.name}", s.description ?: s.prefix, PaletteKind.COMMAND)
        }
        val lsp = activeRecipe()?.let { r ->
            listOf(PaletteItem("cmd.startLsp", "Start language server (${r.languageId})", "run the server in the sandbox", PaletteKind.COMMAND))
        } ?: emptyList()
        val files = flattenFiles(_state.value.fileTree).map { n ->
            PaletteItem("file.${n.docId}", n.name, "open file", PaletteKind.FILE)
        }
        val all = commands + customThemes + snippets + lsp + files
        if (query.isBlank()) return all.take(30)
        return all.filter { fuzzyMatch(query, it.label) }.take(30)
    }

    private fun flattenFiles(nodes: List<FileNode>): List<FileNode> =
        nodes.flatMap { n ->
            if (n.isDir) n.children?.let { flattenFiles(it) } ?: emptyList() else listOf(n)
        }

    private fun fuzzyMatch(query: String, target: String): Boolean {
        var qi = 0
        val q = query.lowercase(); val t = target.lowercase()
        for (c in t) { if (qi < q.length && c == q[qi]) qi++ }
        return qi == q.length
    }

    override fun paletteItemPicked(item: PaletteItem) {
        closePalette()
        when {
            item.id == "cmd.save" -> saveActiveTab()
            item.id == "cmd.newFile" -> newUntitled()
            item.id == "cmd.openFolder" -> _events.tryEmit(IdeEvent.OpenFolderPicker)
            item.id == "cmd.openSandboxHome" -> openSandboxHome()
            item.id == "cmd.newWindow" -> _events.tryEmit(IdeEvent.NewWindow)
            item.id == "cmd.findRefs" -> editor.requestSymbol("editor.findReferences")
            item.id == "cmd.goToDef" -> editor.requestSymbol("editor.goToDefinition")
            item.id == "cmd.reader" -> toggleReader()
            item.id == "cmd.toggleTerminal" -> togglePanel()
            item.id == "cmd.toggleSidebar" -> toggleSidebar()
            item.id == "cmd.importTheme" -> requestImportTheme()
            item.id == "cmd.startLsp" -> startLanguageServer()
            item.id.startsWith("snippet.") -> {
                val i = item.id.removePrefix("snippet.").toIntOrNull()
                activeSnippets.getOrNull(i ?: -1)?.let { editor.insertSnippet(it.body) }
            }
            item.id.startsWith("theme.") -> setTheme(item.id.removePrefix("theme."))
            item.id.startsWith("file.") -> {
                val docId = item.id.removePrefix("file.")
                flattenFiles(_state.value.fileTree).firstOrNull { it.docId == docId }
                    ?.let { openFile(it) }
            }
        }
    }

    /** Surface a one-line message in the status bar (used by the host activity). */
    fun reportStatus(text: String) = _state.update { it.copy(statusText = text) }

    fun requestOpenFolder() { _events.tryEmit(IdeEvent.OpenFolderPicker) }
    fun requestNewWindow() { _events.tryEmit(IdeEvent.NewWindow) }

    /** Open a directory from the Alpine sandbox (e.g. a repo cloned in the terminal). */
    fun openSandboxHome() {
        val sandbox = TerminalHost.service.value?.sandbox
        if (sandbox == null || !sandbox.isInstalled) {
            _state.update { it.copy(statusText = "Install the Linux sandbox first (terminal → Install Linux)") }
            return
        }
        val home = java.io.File(sandbox.rootfsDir, "root")
        if (!home.isDirectory) {
            _state.update { it.copy(statusText = "Alpine home not found — open the terminal first") }
            return
        }
        openFolder(android.net.Uri.fromFile(home))
    }

    // ---------- events from the web editor ----------

    override fun onWebEvent(method: String, params: String) {
        val p = runCatching { JSONObject(params) }.getOrDefault(JSONObject())
        when (method) {
            "editor.ready" -> onEditorReady()
            "editor.dirtyChanged" -> {
                val tabId = p.optString("tabId")
                val dirty = p.optBoolean("dirty")
                _state.update { s ->
                    s.copy(tabs = s.tabs.map { if (it.id == tabId) it.copy(dirty = dirty) else it })
                }
            }
            // Selection-driven navigation (REQ 1/3). No language server needed:
            // both run as a workspace search, which works for every language and
            // every file that's on disk.
            "editor.findReferences" -> symbolSearch(p.optString("text"), definitionsOnly = false)
            "editor.goToDefinition" -> symbolSearch(p.optString("text"), definitionsOnly = true)
            "buffer.save" -> {
                val tabId = p.optString("tabId")
                val text = p.optString("text")
                pendingText.remove(tabId)?.forEach { it(text) }
            }
        }
    }

    /** The WebView (re)loaded: push theme-independent settings and replay open
     *  buffers from disk. Unsaved edits don't survive a WebView recreation yet. */
    private fun onEditorReady() {
        val s = _state.value
        editor.applySettings(s.presets)
        viewModelScope.launch {
            s.tabs.forEach { tab ->
                val text = tab.uri?.let { repo.readText(it) } ?: ""
                editor.openBuffer(tab.id, text, tab.languageId)
            }
            s.activeTabId?.let { editor.showBuffer(it) }
            _state.update { it.copy(statusText = "Editor ready") }
        }
    }

    companion object {

    }
}
