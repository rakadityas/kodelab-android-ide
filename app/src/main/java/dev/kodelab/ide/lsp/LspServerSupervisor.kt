package dev.kodelab.ide.lsp

import dev.kodelab.ide.ext.LspRecipe
import dev.kodelab.ide.terminal.SandboxInstaller
import dev.kodelab.ide.terminal.SandboxProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the lifecycle of language servers running in the sandbox (REQ 3). Servers
 * are *user-installed* (via an extension's LSP recipe `install` steps, run in the
 * terminal) and never bundled; the supervisor only launches the recipe's
 * `command`, speaks LSP to it, and republishes diagnostics.
 */
class LspServerSupervisor(private val sandbox: SandboxInstaller) {

    enum class State { STOPPED, STARTING, RUNNING, FAILED }

    data class Status(val languageId: String, val state: State, val message: String? = null)

    private val _statuses = MutableStateFlow<Map<String, Status>>(emptyMap())
    val statuses: StateFlow<Map<String, Status>> = _statuses.asStateFlow()

    private val _diagnostics = MutableSharedFlow<PublishedDiagnostics>(extraBufferCapacity = 128)
    val diagnostics: SharedFlow<PublishedDiagnostics> = _diagnostics.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Server(val process: SandboxProcess, val client: LspClient)
    private val servers = HashMap<String, Server>()

    val installed: Boolean get() = sandbox.isInstalled

    /** Launch (or return the already-running) server for [recipe]'s language. */
    @Synchronized
    fun start(recipe: LspRecipe, workspaceHostPath: String?): Boolean {
        val lang = recipe.languageId
        if (servers.containsKey(lang)) return true
        if (!sandbox.isInstalled) {
            setStatus(lang, State.FAILED, "Linux sandbox not installed")
            return false
        }
        if (recipe.command.isEmpty()) {
            setStatus(lang, State.FAILED, "recipe has no command")
            return false
        }
        setStatus(lang, State.STARTING)

        val binds = listOfNotNull(workspaceHostPath)
        val process = SandboxProcess(sandbox, recipe.command, workspaceHostPath, binds)
        val client = LspClient(send = { bytes -> process.write(bytes) })

        scope.launch { client.diagnostics.collect { _diagnostics.tryEmit(it) } }
        scope.launch { client.initialized.collect { if (it) setStatus(lang, State.RUNNING) } }

        val ok = process.start(
            onStdout = { b, n -> client.receive(b, n) },
            onStderr = { /* server logs; ignored for now */ },
            onExit = { code ->
                synchronized(this) { servers.remove(lang) }
                setStatus(lang, State.STOPPED, "server exited ($code)")
            },
        )
        if (!ok) {
            setStatus(lang, State.FAILED, "couldn't start server process")
            return false
        }
        servers[lang] = Server(process, client)
        val rootUri = workspaceHostPath?.let { "file://$it" }
        client.initialize(rootUri)
        return true
    }

    fun client(languageId: String): LspClient? = servers[languageId]?.client

    @Synchronized
    fun stop(languageId: String) {
        servers.remove(languageId)?.let {
            runCatching { it.client.shutdown() }
            it.process.close()
        }
        setStatus(languageId, State.STOPPED)
    }

    @Synchronized
    fun stopAll() {
        servers.keys.toList().forEach { stop(it) }
    }

    private fun setStatus(lang: String, state: State, message: String? = null) {
        _statuses.update { it + (lang to Status(lang, state, message)) }
    }
}
