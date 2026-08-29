package dev.kodelab.ide.terminal

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A single shell session on a real pseudo-terminal (our JNI shim over bionic
 * forkpty) — prompts, echo, ^C and job control all behave like a terminal.
 * Falls back to a plain ProcessBuilder pipe if the native lib is missing.
 *
 * Output is fed through [TerminalEmulator], which interprets ANSI colour/SGR and
 * `\r` line-overwrite so tools like `apk` render in colour with in-place progress
 * bars. The UI observes [screen]; [transcript] stays as a plain-text mirror.
 */
class ShellSession(
    val id: String,
    private val cwd: String?,
    /** When set, the session boots into the proot Alpine sandbox instead of /system/bin/sh. */
    private val sandbox: SandboxInstaller? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val output: SharedFlow<String> = _output.asSharedFlow()

    private val emulator = TerminalEmulator()

    /** Styled screen for the UI — one list of spans per line. */
    private val _screen = MutableStateFlow<List<List<TerminalEmulator.Span>>>(emptyList())
    val screen: StateFlow<List<List<TerminalEmulator.Span>>> = _screen.asStateFlow()

    /** Plain-text mirror (scrollback) so late-binding panels still see history. */
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    /** True when running on a real PTY (echo comes from the tty, not from us). */
    var isPty = false
        private set

    private var ptyFd: ParcelFileDescriptor? = null
    private var pid = -1
    private var process: Process? = null
    private var writer: OutputStream? = null

    @Synchronized
    private fun append(chunk: String) {
        emulator.feed(chunk)
        _screen.value = emulator.render()
        _transcript.value = emulator.plainText()
    }

    fun start() {
        val sandboxed = sandbox?.isInstalled == true
        if (Pty.available && startPty(sandboxed)) {
            isPty = true
            append(
                if (sandboxed) {
                    "Kodelab shell — Alpine Linux under proot (fake root)\n" +
                        "apk works: try  apk add git nodejs npm\n\n"
                } else {
                    "Kodelab shell — /system/bin/sh on a real pty\n" +
                        "No package manager here. Tap “Install Linux” in the\n" +
                        "terminal header to set up the Alpine sandbox\n" +
                        "(apk add git, claude code, ...).\n\n"
                },
            )
            return
        }
        startPiped()
    }

    private fun startPty(sandboxed: Boolean): Boolean {
        val outPid = IntArray(1)
        val home = cwd?.let { File(it) }?.takeIf { it.isDirectory && it.canRead() }?.path
        val (argv, envp, workDir) = if (sandboxed) sandboxCommand() else systemCommand(home)
        val fd = Pty.forkExec(
            argv = argv,
            envp = envp,
            cwd = workDir,
            rows = 24, cols = 100,
            outPid = outPid,
        )
        if (fd < 0) return false
        pid = outPid[0]
        return attachPty(fd)
    }

    private fun systemCommand(home: String?): Triple<Array<String>, Array<String>, String?> =
        Triple(
            arrayOf("/system/bin/sh", "-i"),
            arrayOf(
                "TERM=dumb",
                "HOME=${home ?: "/data/local/tmp"}",
                "PATH=/system/bin:/system/xbin",
            ),
            home,
        )

    /**
     * Boot Alpine under proot: fake root (-0, so apk works), bind the host's
     * /dev /proc /sys, and land in /root with a login shell. The proot process
     * itself needs its Termux-built libs on LD_LIBRARY_PATH and a writable
     * PROOT_TMP_DIR.
     */
    private fun sandboxCommand(): Triple<Array<String>, Array<String>, String?> {
        val sb = sandbox!!
        val r = sb.rootfsDir.path
        // On targetSdk 29+ proot is launched through the system linker (execPrefix).
        val argv = (sb.execPrefix + listOf(
            sb.prootBin.path,
            "--link2symlink",
            "-0",
            "-r", r,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/sh", "-l",
        )).toTypedArray()
        val envp = arrayOf(
            "LD_LIBRARY_PATH=${sb.libDir.path}",
            "PROOT_TMP_DIR=${sb.tmpDir.path}",
            "PROOT_LOADER=${sb.prootLoader.path}",
            "PATH=/system/bin:/system/xbin",
            "HOME=${sb.sandboxDir.path}",
            "TERM=xterm-256color",
        )
        return Triple(argv, envp, sb.sandboxDir.path)
    }

    private fun attachPty(fd: Int): Boolean {
        val pfd = ParcelFileDescriptor.adoptFd(fd).also { ptyFd = it }
        writer = ParcelFileDescriptor.AutoCloseOutputStream(pfd.dup())
        pump(ParcelFileDescriptor.AutoCloseInputStream(pfd))
        scope.launch {
            val code = Pty.waitFor(pid)
            append("\n[shell exited ($code)]\n")
        }
        return true
    }

    private fun startPiped() {
        val pb = ProcessBuilder("/system/bin/sh").redirectErrorStream(true)
        cwd?.let { File(it) }?.takeIf { it.isDirectory && it.canRead() }?.let { pb.directory(it) }
        pb.environment()["TERM"] = "dumb"
        val p = pb.start().also { process = it }
        writer = p.outputStream
        append("Kodelab shell — pipe fallback (no pty available)\n\n")
        pump(p.inputStream)
    }

    private fun pump(input: InputStream) {
        scope.launch {
            val buf = ByteArray(4096)
            runCatching {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    append(chunk)
                    _output.emit(chunk)
                }
            }
        }
    }

    /** Raw bytes to the tty — also how ^C (), Tab, Esc and arrows are sent. */
    fun write(data: String) {
        scope.launch {
            runCatching {
                writer?.apply { write(data.toByteArray()); flush() }
            }
        }
    }

    /** Run one command line. A real pty echoes by itself; the pipe fallback doesn't. */
    fun exec(command: String) {
        if (!isPty) append("$ $command\n")
        write(command + "\r")
    }

    fun sendInterrupt() = write("\u0003")

    fun resize(rows: Int, cols: Int) {
        val fd = ptyFd?.fd ?: return
        Pty.resize(fd, rows, cols)
    }

    fun close() {
        runCatching { writer?.close() }
        if (pid > 0) runCatching { Pty.kill(pid) }
        runCatching { process?.destroy() }
        runCatching { ptyFd?.close() }
        scope.cancel()
    }
}
