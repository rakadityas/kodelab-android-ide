package dev.kodelab.ide.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs one-shot, non-interactive commands inside the Alpine/proot sandbox and
 * captures their output — the counterpart to [ShellSession], which is a live
 * interactive PTY. Used by tooling (e.g. the Git panel) that needs a command's
 * exit code and clean stdout rather than a terminal stream.
 *
 * proot is launched as a plain child process (no tty), so stdout/stderr stay
 * separate. The same GPL proot binary is invoked unmodified as a separate
 * process — no Termux code, nothing bundled.
 */
class SandboxShell(private val sandbox: SandboxInstaller) {

    data class Result(val code: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = code == 0
        /** stderr if present, else stdout — for surfacing a failure to the user. */
        fun message(): String = stderr.ifBlank { stdout }.trim()
    }

    val installed: Boolean get() = sandbox.isInstalled

    /**
     * Run `argv` in the guest. [guestCwd] is a path *inside the guest*; when it
     * lives on host storage, pass the host path in [hostBinds] so proot maps it
     * in at the same path. Times out defensively so a hung command can't wedge
     * the caller.
     */
    suspend fun run(
        argv: List<String>,
        guestCwd: String? = null,
        hostBinds: List<String> = emptyList(),
        stdin: String? = null,
        timeoutMs: Long = 30_000,
    ): Result = withContext(Dispatchers.IO) {
        if (!sandbox.isInstalled) {
            return@withContext Result(127, "", "Linux sandbox not installed")
        }
        val command = buildProotArgv(argv, guestCwd, hostBinds)
        val pb = ProcessBuilder(command).directory(sandbox.sandboxDir)
        pb.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", sandbox.libDir.path)
            put("PROOT_TMP_DIR", sandbox.tmpDir.path)
            put("PROOT_LOADER", sandbox.prootLoader.path)
            put("PATH", "/system/bin:/system/xbin")
        }
        val proc = runCatching { pb.start() }.getOrElse {
            return@withContext Result(126, "", it.message ?: "failed to start sandbox")
        }
        // Drain both streams concurrently so a full pipe buffer can't deadlock.
        val outSb = StringBuilder()
        val errSb = StringBuilder()
        val outT = Thread { proc.inputStream.reader().useLines { it.forEach { l -> outSb.append(l).append('\n') } } }
        val errT = Thread { proc.errorStream.reader().useLines { it.forEach { l -> errSb.append(l).append('\n') } } }
        outT.start(); errT.start()
        stdin?.let {
            runCatching { proc.outputStream.use { os -> os.write(it.toByteArray()) } }
        }
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            outT.join(500); errT.join(500)
            return@withContext Result(124, outSb.toString(), "timed out")
        }
        outT.join(1000); errT.join(1000)
        Result(proc.exitValue(), outSb.toString(), errSb.toString())
    }

    private fun buildProotArgv(
        argv: List<String>,
        guestCwd: String?,
        hostBinds: List<String>,
    ): List<String> = buildList {
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
