package dev.kodelab.ide.workspace

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import dev.kodelab.ide.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Publishes the Alpine sandbox home as a storage root, so it shows up in the
 * system file picker's drawer next to Drive and the device storage — a repo
 * cloned in the terminal can then be opened from any "Open from…" dialog,
 * Kodelab's included.
 *
 * The sandbox lives in app-private storage, which no other provider can reach.
 * Document ids are absolute paths, and [resolve] confines every one of them to
 * [root]: a caller that hands back a doctored id (`../../databases/x`) gets a
 * FileNotFoundException, not a file.
 */
class KodelabDocumentsProvider : DocumentsProvider() {

    private val root: File
        get() = File(context!!.filesDir, "sandbox/rootfs/root")

    private val authority: String
        get() = "${context!!.packageName}.documents"

    override fun onCreate(): Boolean = true

    // ---------- roots ----------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_COLUMNS)
        // The picker can't show a root that doesn't exist yet; the sandbox
        // installer is happy to extract into an already-present directory.
        root.mkdirs()
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, docIdOf(root))
            add(Root.COLUMN_TITLE, "Kodelab")
            add(Root.COLUMN_SUMMARY, "Alpine home — the terminal's files")
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY,
            )
        }
        return cursor
    }

    // ---------- documents ----------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOC_COLUMNS)
        addRow(cursor, resolve(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = resolve(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOC_COLUMNS)
        parent.listFiles()
            .orEmpty()
            // Symlinks are everywhere in a rootfs (busybox applets, /root/.cache
            // → elsewhere); following one out of the sandbox is exactly what
            // resolve() forbids, so hide them rather than show broken rows.
            .filter { runCatching { it.canonicalPath.startsWith(canonicalRoot) }.getOrDefault(false) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { addRow(cursor, it) }
        cursor.setNotificationUri(
            context!!.contentResolver,
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId),
        )
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(resolve(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        runCatching {
            val parent = resolve(parentDocumentId).canonicalPath
            resolve(documentId).canonicalPath.startsWith(parent.trimEnd('/') + "/")
        }.getOrDefault(false)

    override fun getDocumentType(documentId: String): String = mimeTypeOf(resolve(documentId))

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = resolve(parentDocumentId)
        val target = uniqueChild(parent, displayName, mimeType == Document.MIME_TYPE_DIR)
        val made =
            if (mimeType == Document.MIME_TYPE_DIR) target.mkdir()
            else runCatching { target.createNewFile() }.getOrDefault(false)
        if (!made) throw FileNotFoundException("Can't create $displayName in ${parent.name}")
        notifyChildrenChanged(parentDocumentId)
        return docIdOf(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolve(documentId)
        if (!file.deleteRecursively()) throw FileNotFoundException("Can't delete ${file.name}")
        file.parentFile?.let { notifyChildrenChanged(docIdOf(it)) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = resolve(documentId)
        val parent = file.parentFile ?: throw FileNotFoundException("No parent for ${file.name}")
        val target = File(parent, displayName)
        if (target.exists()) throw FileNotFoundException("${displayName} already exists")
        if (!file.renameTo(target)) throw FileNotFoundException("Can't rename ${file.name}")
        notifyChildrenChanged(docIdOf(parent))
        return docIdOf(target)
    }

    // ---------- helpers ----------

    private fun addRow(cursor: MatrixCursor, file: File) {
        val isDir = file.isDirectory
        val flags = if (file.canWrite()) {
            (if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE) or
                Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        } else 0
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docIdOf(file))
            add(Document.COLUMN_DISPLAY_NAME, if (file == root) "Alpine home" else file.name)
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"
    }

    private fun docIdOf(file: File): String = file.absolutePath

    private val canonicalRoot: String
        get() = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath).trimEnd('/') + "/"

    /** A document id is only valid while it names something inside the sandbox. */
    private fun resolve(documentId: String): File {
        val file = File(documentId)
        val canonical = runCatching { file.canonicalPath }.getOrNull()
            ?: throw FileNotFoundException("Bad document id")
        val rootPath = canonicalRoot
        if (canonical + "/" != rootPath && !canonical.startsWith(rootPath)) {
            throw FileNotFoundException("Outside the Kodelab sandbox: $documentId")
        }
        if (!file.exists()) throw FileNotFoundException("No such document: $documentId")
        return file
    }

    /** `notes.md`, `notes (1).md`, … so a create never silently overwrites. */
    private fun uniqueChild(parent: File, displayName: String, isDir: Boolean): File {
        val safe = displayName.replace('/', '_').ifBlank { if (isDir) "folder" else "file" }
        var candidate = File(parent, safe)
        if (!candidate.exists()) return candidate
        val base = if (isDir) safe else safe.substringBeforeLast('.', safe)
        val ext = if (isDir) "" else safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (candidate.exists() && n < 1000) {
            candidate = File(parent, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    private fun notifyChildrenChanged(parentDocumentId: String) {
        context?.contentResolver?.notifyChange(
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId),
            null,
        )
    }

    companion object {
        const val ROOT_ID = "kodelab-alpine"

        /** The provider authority, which is package-scoped so debug + release coexist. */
        fun authorityFor(packageName: String) = "$packageName.documents"

        private val DEFAULT_ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS,
        )

        private val DEFAULT_DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        private val TEXT_EXTENSIONS = setOf(
            "md", "markdown", "kt", "kts", "java", "c", "h", "cpp", "hpp", "py", "rb", "rs",
            "go", "sh", "bash", "zsh", "toml", "yml", "yaml", "ini", "cfg", "conf", "gradle",
            "properties", "gitignore", "env", "log", "patch", "diff", "sql", "lua", "pl",
        )
    }
}
