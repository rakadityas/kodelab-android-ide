package dev.kodelab.ide.git

import android.net.Uri
import dev.kodelab.ide.workspace.WorkspaceEntry
import dev.kodelab.ide.workspace.WorkspaceRepository

/**
 * Read a repository's state straight out of `.git`, through the workspace
 * provider rather than through the git binary.
 *
 * This is the path for folders shared from another app: they have no
 * filesystem path, so the sandbox's git can't be pointed at them, but their
 * `.git` is readable like any other file. Enough of it is read to answer "what
 * has changed": HEAD for the branch, the index for what's tracked, and a SHA-1
 * of each tracked file's content to see whether it still matches.
 *
 * Read-only by design. Staging and committing mean writing objects and refs,
 * which is the sandbox's job — this never writes to a repository.
 */
class SafGitReader(private val repo: WorkspaceRepository) {

    sealed interface Result {
        data class Ready(val status: GitStatus) : Result
        data object NotARepo : Result
        data class Unsupported(val reason: String) : Result
    }

    suspend fun read(treeUri: Uri): Result {
        val roots = runCatching { repo.listChildren(treeUri, null) }.getOrElse { return Result.NotARepo }
        val gitDir = roots.firstOrNull { it.isDir && it.name == ".git" } ?: return Result.NotARepo

        val gitEntries = runCatching { repo.listChildren(treeUri, gitDir.docId) }
            .getOrElse { return Result.NotARepo }

        val branch = gitEntries.firstOrNull { it.name == "HEAD" }
            ?.let { repo.readText(it.uri) }
            ?.let { GitIndex.branchFromHead(it) }

        val indexEntry = gitEntries.firstOrNull { it.name == "index" }
            ?: return Result.Ready(GitStatus(branch = branch, detached = branch == null))

        val indexBytes = repo.readBytes(indexEntry.uri)
            ?: return Result.Unsupported("the index is too large to read here")
        val entries = runCatching { GitIndex.parse(indexBytes) }
            .getOrElse { return Result.Unsupported(it.message ?: "unreadable index") }

        val tracked = entries.associateBy { it.path }
        val files = mutableListOf<GitFileStatus>()
        val seen = mutableSetOf<String>()
        var budget = MAX_FILES

        suspend fun walk(parentDocId: String?, prefix: String) {
            if (budget <= 0) return
            val children = runCatching { repo.listChildren(treeUri, parentDocId) }.getOrNull() ?: return
            for (child in children) {
                if (budget <= 0) return
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDir) {
                    if (child.name in SKIP_DIRS) continue
                    walk(child.docId, path)
                } else {
                    budget--
                    seen += path
                    val entry = tracked[path]
                    if (entry == null) {
                        files += GitFileStatus(path = path, index = '?', worktree = '?')
                    } else if (changed(child, entry)) {
                        files += GitFileStatus(path = path, index = ' ', worktree = 'M')
                    }
                }
            }
        }
        walk(null, "")

        // tracked paths the walk never reached have been deleted
        tracked.keys.filterNot { it in seen }
            .take(MAX_FILES)
            .forEach { files += GitFileStatus(path = it, index = ' ', worktree = 'D') }

        return Result.Ready(
            GitStatus(
                branch = branch,
                detached = branch == null,
                files = files.sortedBy { it.path },
            ),
        )
    }

    /** Same size and same content hash means unchanged; anything else is a modification. */
    private suspend fun changed(entry: WorkspaceEntry, indexed: GitIndex.Entry): Boolean {
        val bytes = repo.readBytes(entry.uri, MAX_HASH_BYTES) ?: return false
        if (bytes.size.toLong() != indexed.size) return true
        return GitIndex.blobSha(bytes) != indexed.sha
    }

    private companion object {
        /** Bounded so a huge repo can't hang the panel. */
        const val MAX_FILES = 2000
        const val MAX_HASH_BYTES = 4 shl 20
        val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea")
    }
}
