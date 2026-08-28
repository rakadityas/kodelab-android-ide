package dev.kodelab.ide.ui

import android.net.Uri
import dev.kodelab.ide.git.GitFileStatus
import dev.kodelab.ide.git.GitStatus
import dev.kodelab.ide.theme.EditorPalette
import dev.kodelab.ide.workspace.FileMatches
import dev.kodelab.ide.workspace.SearchMatch
import dev.kodelab.ide.workspace.WorkspacePresets

data class EditorTab(
    val id: String,
    val title: String,
    val uri: Uri?,              // null == untitled / virtual buffer
    val languageId: String,
    val dirty: Boolean = false,
    val preview: Boolean = false, // italic, replaced by next single-click open
    val pinned: Boolean = false,
)

enum class SidebarView { EXPLORER, SEARCH, GIT, EXTENSIONS }

/** A user-imported theme (from a JSON file under `.kodelab/themes`), resolved to a palette. */
data class CustomTheme(val id: String, val name: String, val palette: EditorPalette)

/** A declarative extension discovered in the workspace, as shown in the panel. */
data class LoadedExtension(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String?,
    val license: String?,
    val description: String?,
    val allowed: Boolean,
    val summary: String,
    val issues: List<String>,
)

/** Why the Git panel can or can't show a status, mapped from GitService.RepoState. */
enum class GitAvailability { UNKNOWN, SANDBOX_MISSING, GIT_MISSING, NOT_A_REPO, NO_PATH, READY, ERROR }

data class GitUiState(
    val availability: GitAvailability = GitAvailability.UNKNOWN,
    val status: GitStatus? = null,
    val message: String? = null,
    val loading: Boolean = false,
    val commitMessage: String = "",
    /** Path of a file with an operation (stage/unstage) in flight, for disabling its row. */
    val busyPath: String? = null,
)

data class FileNode(
    val name: String,
    val uri: Uri,
    val docId: String,
    val isDir: Boolean,
    val depth: Int,
    val expanded: Boolean = false,
    val children: List<FileNode>? = null, // null == not loaded yet (lazy)
)

/** One entry in the command palette. */
data class PaletteItem(
    val id: String,
    val label: String,
    val detail: String? = null,
    val kind: PaletteKind,
)

enum class PaletteKind { COMMAND, FILE, THEME }

enum class FileOpKind { NEW_FILE, NEW_FOLDER, RENAME, DELETE }

/** A pending Explorer operation awaiting the user's dialog input/confirmation. */
data class FileOpRequest(
    val kind: FileOpKind,
    /** Directory the op runs in (null == workspace root) or, for RENAME/DELETE, the target. */
    val target: FileNode?,
    val initialName: String = "",
)

data class IdeUiState(
    val workspaceName: String = "No folder open",
    val workspaceUri: Uri? = null,
    val presets: WorkspacePresets = WorkspacePresets.Defaults,
    val tabs: List<EditorTab> = emptyList(),
    val activeTabId: String? = null,
    val sidebarVisible: Boolean = true,
    val sidebarView: SidebarView = SidebarView.EXPLORER,
    val panelVisible: Boolean = false, // integrated terminal panel
    val fileTree: List<FileNode> = emptyList(),
    val statusText: String = "Ready",
    val paletteOpen: Boolean = false,
    val paletteQuery: String = "",
    val paletteItems: List<PaletteItem> = emptyList(),
    /** Tab awaiting a keep/discard decision because it has unsaved changes. */
    val pendingCloseTabId: String? = null,
    val pendingFileOp: FileOpRequest? = null,
    // --- search across files ---
    val searchQuery: String = "",
    val searchResults: List<FileMatches> = emptyList(),
    val searching: Boolean = false,
    /** null = no search run yet; otherwise a "12 results in 4 files" style summary. */
    val searchSummary: String? = null,
    // --- git panel ---
    val git: GitUiState = GitUiState(),
    /** Imported themes available in this workspace, keyed for selection. */
    val customThemes: List<CustomTheme> = emptyList(),
    /** Declarative extensions discovered in this workspace. */
    val extensions: List<LoadedExtension> = emptyList(),
)

/** Everything the UI can ask the ViewModel to do. */
interface IdeActions {
    fun selectTab(id: String)
    fun closeTab(id: String)
    fun confirmCloseDiscard()
    fun cancelClose()
    fun newUntitled()
    fun toggleSidebar()
    fun togglePanel()
    fun setSidebarView(view: SidebarView)
    fun searchQueryChanged(query: String)
    /** Run the search now (e.g. keyboard "search" action); no-op if the query is blank. */
    fun runSearch()
    fun openSearchHit(file: FileMatches, match: SearchMatch)
    // --- git panel ---
    fun gitRefresh()
    fun gitStage(file: GitFileStatus)
    fun gitUnstage(file: GitFileStatus)
    fun gitStageAll()
    fun gitCommitMessageChanged(message: String)
    fun gitCommit()
    fun openGitDiff(file: GitFileStatus, staged: Boolean)
    fun setTheme(themeId: String)
    fun cycleTheme()
    /** Ask the host to pick a theme JSON file to import. */
    fun requestImportTheme()
    /** Parse + persist a picked theme JSON and switch to it. */
    fun importThemeFrom(uri: Uri)
    /** Start the language server whose recipe matches the active file's language. */
    fun startLanguageServer()
    fun openPalette()
    fun closePalette()
    fun paletteQueryChanged(query: String)
    fun paletteItemPicked(item: PaletteItem)
    fun openFolder(treeUri: Uri)
    fun toggleDir(node: FileNode)
    fun openFile(node: FileNode)
    fun saveActiveTab()
    fun requestFileOp(kind: FileOpKind, target: FileNode?)
    fun confirmFileOp(name: String)
    fun cancelFileOp()
    /** Accessory-bar key: runs a Monaco command ("tab", "undo", "cursorLeft", ...). */
    fun sendEditorCommand(command: String)
    /** Accessory-bar key: inserts literal text at the cursor. */
    fun sendEditorText(text: String)
    fun onWebEvent(method: String, params: String)
}
