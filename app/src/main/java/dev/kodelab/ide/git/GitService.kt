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

    /** [guestPath] is the repo as the *guest* sees it — see SandboxInstaller.guestLocationOf. */
    private fun git(guestPath: String, vararg args: String) =
        listOf("git", "-C", guestPath, *args)

    suspend fun refresh(hostPath: String?): RepoState {
        if (!shell.installed) return RepoState.SandboxMissing
        if (hostPath.isNullOrBlank()) return RepoState.NoPath
        val where = shell.guestLocationOf(hostPath)
        val binds = listOfNotNull(where.bind)

        val version = shell.run(listOf("git", "--version"))
        if (!version.ok) return RepoState.GitMissing

        val inside = shell.run(git(where.path, "rev-parse", "--is-inside-work-tree"), where.path, binds)
        if (!inside.ok || inside.stdout.trim() != "true") return RepoState.NotARepo

        val status = shell.run(git(where.path, "status", "--porcelain=v1", "--branch"), where.path, binds)
        if (!status.ok) return RepoState.Error(status.message())
        return RepoState.Ready(GitStatus.parse(status.stdout))
    }

    private suspend fun inRepo(hostPath: String, vararg args: String): SandboxShell.Result {
        val where = shell.guestLocationOf(hostPath)
        return shell.run(git(where.path, *args), where.path, listOfNotNull(where.bind))
    }

    suspend fun stage(hostPath: String, path: String): SandboxShell.Result =
        inRepo(hostPath, "add", "--", path)

    suspend fun stageAll(hostPath: String): SandboxShell.Result =
        inRepo(hostPath, "add", "-A")

    suspend fun unstage(hostPath: String, path: String): SandboxShell.Result =
        inRepo(hostPath, "reset", "-q", "HEAD", "--", path)

    suspend fun commit(hostPath: String, message: String): SandboxShell.Result =
        inRepo(hostPath, "commit", "-m", message)

    /** Unified diff for one file; [staged] shows the index diff, else the worktree diff. */
    suspend fun diff(hostPath: String, path: String, staged: Boolean): SandboxShell.Result =
        if (staged) inRepo(hostPath, "diff", "--staged", "--", path)
        else inRepo(hostPath, "diff", "--", path)
}
