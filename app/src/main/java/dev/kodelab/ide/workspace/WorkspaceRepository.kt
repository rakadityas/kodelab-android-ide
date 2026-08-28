package dev.kodelab.ide.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * All folder/file access goes through the Storage Access Framework: the user
 * picks a tree, we persist the permission, and every read/write is scoped to
 * that tree. Kodelab never asks for broad storage permissions.
 */
class WorkspaceRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** Keep the grant across restarts so recent folders reopen without re-picking. */
    fun persistPermission(treeUri: Uri) {
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun workspaceName(treeUri: Uri): String =
        DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: treeUri.lastPathSegment?.substringAfterLast('/')
            ?: "workspace"

    /**
     * One directory level; children load lazily on expand to keep big repos fast.
     * Pass the tree root's document id (or a child's) — a single provider query
     * per level, much faster than DocumentFile.listFiles().
     */
    suspend fun listChildren(treeUri: Uri, parentDocId: String?): List<WorkspaceEntry> =
        withContext(Dispatchers.IO) {
            val parent = parentDocId ?: DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            val out = mutableListOf<WorkspaceEntry>()
            runCatching {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        out += WorkspaceEntry(
                            name = name,
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            docId = docId,
                            isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        )
                    }
                }
            }
            out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        }

    suspend fun readText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    suspend fun writeText(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // "wt" truncates; plain "w" can leave stale bytes on some providers
            resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                ?: return@withContext false
            true
        }.getOrDefault(false)
    }

    // --- file operations (Explorer context menu) ---

    suspend fun createFile(parentUri: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.createDocument(resolver, parentUri, mimeFor(name), name)
        }.getOrNull()
    }

    suspend fun createDirectory(parentUri: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.createDocument(
                resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name,
            )
        }.getOrNull()
    }

    suspend fun rename(docUri: Uri, newName: String): Uri? = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.renameDocument(resolver, docUri, newName) }.getOrNull()
    }

    suspend fun delete(docUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(resolver, docUri) }.getOrDefault(false)
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "md" -> "text/markdown"
        "html", "htm" -> "text/html"
        else -> "text/plain"
    }

    // --- per-folder presets (.kodelab/workspace.json) ---

    private suspend fun presetsFile(treeUri: Uri, create: Boolean): DocumentFile? =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext null
            val dir = root.findFile(PRESETS_DIR)?.takeIf { it.isDirectory }
                ?: if (create) root.createDirectory(PRESETS_DIR) else null
            dir?.let { d ->
                d.findFile(PRESETS_FILE)
                    ?: if (create) d.createFile("application/json", PRESETS_FILE) else null
            }
        }

    suspend fun loadPresets(treeUri: Uri): WorkspacePresets {
        val file = presetsFile(treeUri, create = false) ?: return WorkspacePresets.Defaults
        val text = readText(file.uri) ?: return WorkspacePresets.Defaults
        return WorkspacePresets.parse(text)
    }

    suspend fun savePresets(treeUri: Uri, presets: WorkspacePresets): Boolean {
        val file = presetsFile(treeUri, create = true) ?: return false
        return writeText(file.uri, WorkspacePresets.serialize(presets))
    }

    companion object {
        const val PRESETS_DIR = ".kodelab"
        const val PRESETS_FILE = "workspace.json"

        /** Best-effort local filesystem path for a tree uri (for the terminal's cwd). */
        fun localPathOf(treeUri: Uri): String? {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return null
            val (volume, path) = docId.split(':', limit = 2).let {
                if (it.size == 2) it[0] to it[1] else return null
            }
            return if (volume == "primary") "/storage/emulated/0/$path" else "/storage/$volume/$path"
        }
    }
}

data class WorkspaceEntry(val name: String, val uri: Uri, val docId: String, val isDir: Boolean)
