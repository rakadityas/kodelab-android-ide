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
import dev.kodelab.ide.lsp.LspServerSupervisor
import dev.kodelab.ide.terminal.SandboxShell
import dev.kodelab.ide.terminal.TerminalHost
import dev.kodelab.ide.theme.KodelabThemes
import dev.kodelab.ide.theme.VsThemeImport
import dev.kodelab.ide.workspace.FileMatches
import dev.kodelab.ide.workspace.SearchMatch
import dev.kodelab.ide.workspace.SettingsStore
import dev.kodelab.ide.workspace.WorkspacePresets
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
    /** Last text received from the web layer per tab — what "save" writes. */
    private val pendingSaves = mutableMapOf<String, (String) -> Unit>()

    init {
        viewModelScope.launch {
            val user = settings.settings.first()
            _state.update { s -> s.copy(presets = s.presets.withUserDefaults(user)) }
        }
    }

    private fun seedState(): IdeUiState {
        val welcome = EditorTab(
            id = UUID.randomUUID().toString(),
            title = "Welcome",
            uri = null,
            languageId = "markdown",
        )
        return IdeUiState(
            tabs = listOf(welcome),
            activeTabId = welcome.id,
            statusText = "Open a folder to begin",
        )
    }

    // ---------- workspace ----------

    override fun openFolder(treeUri: Uri) {
        viewModelScope.launch {
            runCatching { repo.persistPermission(treeUri) }
            val name = repo.workspaceName(treeUri)
            val presets = repo.loadPresets(treeUri).withUserDefaults(settings.settings.first())
            val roots = repo.listChildren(treeUri, null).map { e ->
                FileNode(e.name, e.uri, e.docId, e.isDir, depth = 0)
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
                    sidebarVisible = true,
                    sidebarView = SidebarView.EXPLORER,
                    statusText = "Opened $name",
                )
            }
            editor.applySettings(presets)
        }
    }

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
        val existing = _state.value.tabs.firstOrNull { it.uri == uri }
        if (existing != null) {
            selectTab(existing.id)
            revealLine?.let { editor.revealLine(existing.id, it) }
            return
        }
        viewModelScope.launch {
            val text = repo.readText(uri)
            if (text == null) {
                _state.update { it.copy(statusText = "Can't read $name") }
                return@launch
            }
            val lang = Languages.forFileName(name)
            val tab = EditorTab(UUID.randomUUID().toString(), name, uri, lang)
            _state.update { s ->
                // a new open replaces the current preview tab, VS-style
                val tabs = s.tabs.filterNot { it.preview && !it.dirty } + tab.copy(preview = false)
                s.copy(tabs = tabs, activeTabId = tab.id, statusText = name)
            }
            editor.openBuffer(tab.id, text, lang)
            editor.showBuffer(tab.id)
            revealLine?.let { editor.revealLine(tab.id, it) }
        }
    }

    override fun selectTab(id: String) {
        _state.update { it.copy(activeTabId = id) }
        editor.showBuffer(id)
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
        pendingSaves[tab.id] = { text -> persistTab(tab, text) }
        editor.requestSave(tab.id)
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
        _state.value.workspaceUri?.let { WorkspaceRepository.localPathOf(it) }

    override fun gitRefresh() {
        val svc = gitService()
        if (svc == null) {
            _state.update { it.copy(git = it.git.copy(availability = GitAvailability.SANDBOX_MISSING, loading = false)) }
            return
        }
        _state.update { it.copy(git = it.git.copy(loading = true)) }
        viewModelScope.launch {
            val result = svc.refresh(hostPath())
            _state.update { it.copy(git = it.git.applyRepoState(result)) }
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
            s.copy(tabs = tabs, activeTabId = tab.id, statusText = title)
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
            PaletteItem("cmd.newWindow", "New window", "another Kodelab window, same terminal", PaletteKind.COMMAND),
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
            item.id == "cmd.newWindow" -> _events.tryEmit(IdeEvent.NewWindow)
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

    fun requestOpenFolder() { _events.tryEmit(IdeEvent.OpenFolderPicker) }
    fun requestNewWindow() { _events.tryEmit(IdeEvent.NewWindow) }

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
            "buffer.save" -> {
                val tabId = p.optString("tabId")
                val text = p.optString("text")
                pendingSaves.remove(tabId)?.invoke(text)
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
                val text = tab.uri?.let { repo.readText(it) }
                    ?: if (tab.title == "Welcome") WELCOME_TEXT else ""
                editor.openBuffer(tab.id, text, tab.languageId)
            }
            s.activeTabId?.let { editor.showBuffer(it) }
            _state.update { it.copy(statusText = "Editor ready") }
        }
    }

    companion object {
        private val WELCOME_TEXT = """
            # Welcome to Kodelab

            A code IDE that runs on this Android device.

            - Open a folder: tap the folder icon, or run "Open folder…" from the
              command palette (the search icon in the status bar).
            - Tabs, themes, and editor settings follow the folder you open —
              they live in `.kodelab/workspace.json` inside it.
            - The terminal (rail icon) is shared across every folder and window.

            This build is an early milestone; the Linux sandbox (`apk add git`,
            Claude Code) and language servers arrive next.
        """.trimIndent()
    }
}
