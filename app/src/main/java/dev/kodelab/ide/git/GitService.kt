package dev.kodelab.ide.git

import dev.kodelab.ide.terminal.SandboxShell

/**
 * Thin wrapper that runs `git` inside the Linux sandbox and turns its output
 * into typed results. Git itself is whatever the user installed with
 * `apk add git`; Kodelab bundles no Git implementation.
 *
 * All operations take the workspace's *host* path (from
 * WorkspaceRepository.localPathOf); it is bound into proot at the same path so
 * `git -C <path>` sees the real repository.
 */
class GitService(private val shell: SandboxShell) {

    sealed interface RepoState {
        data object SandboxMissing : RepoState
        data object GitMissing : RepoState
        data object NotARepo : RepoState
        data object NoPath : RepoState
        data class Ready(val status: GitStatus) : RepoState
        data class Error(val message: String) : RepoState
    }

    private fun git(hostPath: String, vararg args: String) =
        listOf("git", "-C", hostPath, *args)

    suspend fun refresh(hostPath: String?): RepoState {
        if (!shell.installed) return RepoState.SandboxMissing
        if (hostPath.isNullOrBlank()) return RepoState.NoPath
        val binds = listOf(hostPath)

        val version = shell.run(listOf("git", "--version"))
        if (!version.ok) return RepoState.GitMissing

        val inside = shell.run(git(hostPath, "rev-parse", "--is-inside-work-tree"), hostPath, binds)
        if (!inside.ok || inside.stdout.trim() != "true") return RepoState.NotARepo

        val status = shell.run(git(hostPath, "status", "--porcelain=v1", "--branch"), hostPath, binds)
        if (!status.ok) return RepoState.Error(status.message())
        return RepoState.Ready(GitStatus.parse(status.stdout))
    }

    suspend fun stage(hostPath: String, path: String): SandboxShell.Result =
        shell.run(git(hostPath, "add", "--", path), hostPath, listOf(hostPath))

    suspend fun stageAll(hostPath: String): SandboxShell.Result =
        shell.run(git(hostPath, "add", "-A"), hostPath, listOf(hostPath))

    suspend fun unstage(hostPath: String, path: String): SandboxShell.Result =
        shell.run(git(hostPath, "reset", "-q", "HEAD", "--", path), hostPath, listOf(hostPath))

    suspend fun commit(hostPath: String, message: String): SandboxShell.Result =
        shell.run(git(hostPath, "commit", "-m", message), hostPath, listOf(hostPath))

    /** Unified diff for one file; [staged] shows the index diff, else the worktree diff. */
    suspend fun diff(hostPath: String, path: String, staged: Boolean): SandboxShell.Result {
        val args = if (staged) {
            git(hostPath, "diff", "--staged", "--", path)
        } else {
            git(hostPath, "diff", "--", path)
        }
        return shell.run(args, hostPath, listOf(hostPath))
    }
}
