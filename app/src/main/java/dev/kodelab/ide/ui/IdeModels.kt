package dev.kodelab.ide.ui

import android.net.Uri
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
    fun setTheme(themeId: String)
    fun cycleTheme()
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
