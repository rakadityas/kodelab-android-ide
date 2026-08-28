package dev.kodelab.ide.git

/**
 * Parsed view of `git status --porcelain=v1 --branch` — enough to drive a
 * status/stage/commit panel without depending on any Git library. All parsing
 * lives here as pure functions so it is unit-testable off-device.
 */
data class GitFileStatus(
    /** Working-tree path (for renames, the new path). */
    val path: String,
    /** Original path for a rename/copy, else null. */
    val origPath: String? = null,
    /** Index (staged) status char: M A D R C U, ' ' none, '?' untracked. */
    val index: Char,
    /** Working-tree (unstaged) status char. */
    val worktree: Char,
) {
    val untracked: Boolean get() = index == '?' && worktree == '?'
    val conflicted: Boolean get() = index == 'U' || worktree == 'U' || (index == 'D' && worktree == 'D') || (index == 'A' && worktree == 'A')
    /** Has an index change to unstage (excludes untracked). */
    val staged: Boolean get() = !untracked && index != ' '
    /** Has a working-tree change (modification or untracked) to stage. */
    val unstaged: Boolean get() = untracked || worktree != ' '

    /** Short one-letter badge for the UI, favouring the more relevant side. */
    fun badge(): Char = when {
        untracked -> 'U'
        conflicted -> '!'
        index != ' ' -> index
        else -> worktree
    }
}

data class GitStatus(
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val detached: Boolean = false,
    val files: List<GitFileStatus> = emptyList(),
) {
    val staged: List<GitFileStatus> get() = files.filter { it.staged }
    val unstaged: List<GitFileStatus> get() = files.filter { it.unstaged }
    val clean: Boolean get() = files.isEmpty()

    companion object {
        /**
         * Parse the output of `git status --porcelain=v1 --branch`.
         * Each entry line is `XY<space>PATH` (renames: `R  old -> new`); the
         * first line, if present, is the `## branch...upstream [ahead/behind]`
         * header.
         */
        fun parse(output: String): GitStatus {
            var branch: String? = null
            var upstream: String? = null
            var ahead = 0
            var behind = 0
            var detached = false
            val files = mutableListOf<GitFileStatus>()

            for (line in output.split('\n')) {
                if (line.isEmpty()) continue
                if (line.startsWith("## ")) {
                    val header = parseBranchHeader(line.substring(3))
                    branch = header.branch
                    upstream = header.upstream
                    ahead = header.ahead
                    behind = header.behind
                    detached = header.detached
                    continue
                }
                if (line.length < 3) continue
                val index = line[0]
                val worktree = line[1]
                // column 2 is a space separator
                val rest = line.substring(3)
                val (path, orig) = if (rest.contains(" -> ")) {
                    val parts = rest.split(" -> ", limit = 2)
                    parts[1] to parts[0]
                } else {
                    rest to null
                }
                files += GitFileStatus(unquote(path), orig?.let { unquote(it) }, index, worktree)
            }
            return GitStatus(branch, upstream, ahead, behind, detached, files)
        }

        private data class BranchHeader(
            val branch: String?,
            val upstream: String?,
            val ahead: Int,
            val behind: Int,
            val detached: Boolean,
        )

        private fun parseBranchHeader(text: String): BranchHeader {
            // Cases:
            //  "No commits yet on main"
            //  "HEAD (no branch)"
            //  "main"
            //  "main...origin/main"
            //  "main...origin/main [ahead 1, behind 2]"
            if (text.startsWith("No commits yet on ")) {
                return BranchHeader(text.removePrefix("No commits yet on ").trim(), null, 0, 0, false)
            }
            if (text.startsWith("HEAD (no branch)")) {
                return BranchHeader(null, null, 0, 0, true)
            }
            val trackStart = text.indexOf("...")
            if (trackStart < 0) {
                return BranchHeader(text.trim(), null, 0, 0, false)
            }
            val branch = text.substring(0, trackStart).trim()
            var rest = text.substring(trackStart + 3)
            var ahead = 0
            var behind = 0
            val bracket = rest.indexOf(" [")
            if (bracket >= 0) {
                val inside = rest.substring(bracket + 2).trimEnd(']', ' ')
                rest = rest.substring(0, bracket)
                for (part in inside.split(',')) {
                    val p = part.trim()
                    when {
                        p.startsWith("ahead ") -> ahead = p.removePrefix("ahead ").trim().toIntOrNull() ?: 0
                        p.startsWith("behind ") -> behind = p.removePrefix("behind ").trim().toIntOrNull() ?: 0
                    }
                }
            }
            return BranchHeader(branch, rest.trim().ifEmpty { null }, ahead, behind, false)
        }

        /** Git quotes paths with unusual characters in double quotes; strip them. */
        private fun unquote(p: String): String =
            if (p.length >= 2 && p.startsWith('"') && p.endsWith('"')) {
                p.substring(1, p.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else {
                p
            }
    }
}
