package dev.kodelab.ide.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream

/**
 * A long-lived, non-interactive process inside the Alpine/proot sandbox with raw
 * (non-PTY) stdio. Language servers need clean byte streams for `Content-Length`
 * framing — a PTY would corrupt them with echo and line discipline — so this is
 * the transport used by the LSP layer, distinct from [ShellSession] (PTY) and
 * [SandboxShell] (one-shot capture).
 */
class SandboxProcess(
    private val sandbox: SandboxInstaller,
    private val argv: List<String>,
    private val guestCwd: String? = null,
    private val hostBinds: List<String> = emptyList(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var stdin: OutputStream? = null

    /** @return true if the process launched. [onStdout] gets raw bytes; [onExit] the code. */
    fun start(
        onStdout: (ByteArray, Int) -> Unit,
        onStderr: (String) -> Unit = {},
        onExit: (Int) -> Unit = {},
    ): Boolean {
        if (!sandbox.isInstalled) return false
        val pb = ProcessBuilder(buildProotArgv()).directory(sandbox.sandboxDir)
        pb.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", sandbox.libDir.path)
            put("PROOT_TMP_DIR", sandbox.tmpDir.path)
            put("PROOT_LOADER", sandbox.prootLoader.path)
            put("PATH", "/system/bin:/system/xbin")
        }
        val proc = runCatching { pb.start() }.getOrElse { return false }
        process = proc
        stdin = proc.outputStream
        scope.launch {
            val buf = ByteArray(8192)
            runCatching {
                val input = proc.inputStream
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) onStdout(buf, n)
                }
            }
        }
        scope.launch {
            runCatching { proc.errorStream.reader().useLines { it.forEach(onStderr) } }
        }
        scope.launch {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            onExit(code)
        }
        return true
    }

    fun write(bytes: ByteArray) {
        scope.launch { runCatching { stdin?.apply { write(bytes); flush() } } }
    }

    fun close() {
        runCatching { stdin?.close() }
        runCatching { process?.destroy() }
        scope.cancel()
    }

    private fun buildProotArgv(): List<String> = buildList {
        addAll(sandbox.execPrefix) // launch proot via the system linker on targetSdk 29+
        add(sandbox.prootBin.path)
        add("--link2symlink")
        add("-0")
        add("-r"); add(sandbox.rootfsDir.path)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        for (bind in hostBinds) { add("-b"); add(bind) }
        add("-w"); add(guestCwd ?: "/root")
        add("/usr/bin/env"); add("-i")
        add("HOME=/root")
        add("TERM=dumb")
        add("LANG=C.UTF-8")
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        addAll(argv)
    }
}
