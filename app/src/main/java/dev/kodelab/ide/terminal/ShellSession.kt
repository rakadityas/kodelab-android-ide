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
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

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

    // Terminal queries (cursor position, device attributes) are answered
    // straight back to the pty: a prompt library that asks and gets no reply
    // blocks forever, which is what froze `gh auth login` mid-question.
    private val emulator = TerminalEmulator(
        onReply = { reply -> write(reply) },
        onOpenUrl = { url -> scope.launch { _openUrl.emit(url) } },
    )

    /**
     * URLs the guest asked the phone to open — `gh auth login` and friends going
     * through the sandbox's `xdg-open` shim. Collected by the terminal panel,
     * which hands them to the browser.
     */
    private val _openUrl = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val openUrl: SharedFlow<String> = _openUrl.asSharedFlow()

    /** Styled screen for the UI — one list of spans per line. */
    private val _screen = MutableStateFlow<List<List<TerminalEmulator.Span>>>(emptyList())
    val screen: StateFlow<List<List<TerminalEmulator.Span>>> = _screen.asStateFlow()

    /** Where the cursor is in [screen]: line index, column, and whether to draw it. */
    data class Cursor(val row: Int, val col: Int, val visible: Boolean)

    private val _cursor = MutableStateFlow(Cursor(0, 0, true))
    val cursor: StateFlow<Cursor> = _cursor.asStateFlow()

    /**
     * DECCKM: while a full-screen program has it set, arrows must be sent as
     * `ESC O A` rather than `ESC [ A` (see [TerminalKeys.cursorKey]).
     */
    val applicationCursorKeys: Boolean get() = emulator.applicationCursorKeys

    /** Plain-text mirror (scrollback) so late-binding panels still see history. */
    /** False once the shell has exited (ctrl-D, `exit`, or a crash). */
    private val _alive = MutableStateFlow(true)
    val alive: StateFlow<Boolean> = _alive.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    /** True when running on a real PTY (echo comes from the tty, not from us). */
    var isPty = false
        private set

    private var ptyFd: ParcelFileDescriptor? = null
    private var lastRows = 0
    private var lastCols = 0
    private var pid = -1
    private var process: Process? = null
    private var writer: OutputStream? = null

    @Synchronized
    private fun append(chunk: String) {
        emulator.feed(chunk)
        publish()
    }

    private fun publish() {
        _screen.value = emulator.render()
        _transcript.value = emulator.plainText()
        _cursor.value = Cursor(emulator.cursorRow, emulator.cursorCol, emulator.cursorVisible)
    }

    fun start() {
        val sandboxed = sandbox?.isInstalled == true
        // Refresh the guest's colour profile: an Alpine installed by an older
        // build predates it, and there's no install step to hang it off.
        if (sandboxed) sandbox?.ensureShellProfile()
        if (Pty.available && startPty(sandboxed)) {
            isPty = true
            append(
                if (sandboxed) {
                    "Kodelab shell — Alpine Linux under proot (fake root)\n" +
                        "curl, git, ssh and a terminfo database are in; add what\n" +
                        "you like with apk:  apk add nodejs npm github-cli go …\n\n"
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
        val (argv, envp, workDir) = if (sandboxed) sandboxCommand(home) else systemCommand(home)
        val fd = Pty.forkExec(
            argv = argv,
            envp = envp,
            cwd = workDir,
            // Placeholder geometry: the view measures itself and calls
            // resize() with the real window size as soon as it is laid out.
            rows = if (lastRows > 0) lastRows else 24,
            cols = if (lastCols > 0) lastCols else 80,
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
    private fun sandboxCommand(hostCwd: String?): Triple<Array<String>, Array<String>, String?> {
        val sb = sandbox!!
        val r = sb.rootfsDir.path
        // Land in the open folder rather than /root. A folder inside the rootfs
        // is already visible to the guest; anything else has to be bound in.
        val where = hostCwd?.let { sb.guestLocationOf(it) }
        val binds = listOfNotNull(where?.bind).flatMap { listOf("-b", it) }
        // On targetSdk 29+ proot is launched through the system linker (execPrefix).
        val argv = (sb.execPrefix + listOf(
            sb.prootBin.path,
            "--link2symlink",
            "-0",
            "-r", r,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
        ) + binds + listOf(
            "-w", (where?.path ?: "/root"),
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            // What Go's browser package (so `gh`), python's webbrowser and
            // most CLIs check before giving up and printing a URL to copy.
            "BROWSER=/usr/local/bin/xdg-open",
            "/bin/sh", "-l",
        )).toTypedArray()
        val envp = arrayOf(
            "LD_LIBRARY_PATH=${sb.libDir.path}",
            "PROOT_TMP_DIR=${sb.tmpDir.path}",
            "PROOT_LOADER=${sb.prootLoader.path}",
            // Some Android 12+ kernels reject proot's seccomp filter, which
            // surfaces as `execve(...): Permission denied` on the first guest
            // command. Disabling it keeps exec working (slightly slower syscalls).
            "PROOT_NO_SECCOMP=1",
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
            append("\n[shell exited ($code)] — tap Restart, or + for a new terminal\n")
            _alive.value = false
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
            // Decode across reads: a multi-byte character (box drawing, an
            // emoji in a spinner) can straddle two reads, and decoding each
            // chunk on its own turns those into replacement blocks.
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val pending = ByteBuffer.allocate(8192)
            val chars = CharBuffer.allocate(8192)
            runCatching {
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    pending.put(buf, 0, n)
                    pending.flip()
                    decoder.decode(pending, chars, false)
                    pending.compact() // whatever is left is a partial character
                    chars.flip()
                    val chunk = chars.toString()
                    chars.clear()
                    if (chunk.isEmpty()) continue
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

    /**
     * Paste text at the prompt. A program that asked for bracketed paste
     * (DECSET 2004 — shells, editors, claude) gets it wrapped in the markers
     * that tell it this was pasted rather than typed, so a multi-line paste
     * doesn't run itself line by line.
     */
    fun paste(text: String) {
        if (text.isEmpty()) return
        val body = text.replace("\r\n", "\r").replace("\n", "\r")
        write(if (emulator.bracketedPaste) "\u001B[200~" + body + "\u001B[201~" else body)
    }

    /**
     * Follow the view's real size. Both halves matter: the pty's winsize is what
     * programs read to lay themselves out, and the emulator needs the same
     * geometry or its cursor arithmetic disagrees with theirs.
     */
    @Synchronized
    fun resize(rows: Int, cols: Int) {
        if (rows < 1 || cols < 1) return
        if (rows == lastRows && cols == lastCols) return
        lastRows = rows
        lastCols = cols
        emulator.resize(rows, cols)
        publish()
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
