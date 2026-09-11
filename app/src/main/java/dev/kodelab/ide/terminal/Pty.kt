package dev.kodelab.ide.terminal

/**
 * JNI bindings to the first-party PTY shim (src/main/cpp/kodelab_pty.c).
 * Written against POSIX pty(7)/termios; no third-party terminal code.
 */
object Pty {

    val available: Boolean = runCatching { System.loadLibrary("kodelab_pty") }.isSuccess

    /**
     * Fork+exec [argv] on a new pseudo-terminal.
     * @return the master fd (>= 0), or -errno on failure; child pid lands in [outPid]`[0]`.
     */
    external fun forkExec(
        argv: Array<String>,
        envp: Array<String>?,
        cwd: String?,
        rows: Int,
        cols: Int,
        outPid: IntArray,
    ): Int

    external fun resize(fd: Int, rows: Int, cols: Int)

    /** Blocks until the child exits; exit code, or -signal. */
    external fun waitFor(pid: Int): Int

    external fun kill(pid: Int)
}
