package dev.kodelab.ide.terminal

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.kodelab.ide.KodelabApp
import dev.kodelab.ide.R

/**
 * Device-wide, shared terminal host (REQ 5 / REQ 8). Every window and every
 * workspace binds this same service, so a running build or a logged-in `claude`
 * session survives folder switches and app backgrounding.
 *
 * M0: sessions are backed by [ShellSession] (ProcessBuilder over /system/bin/sh).
 * Next milestone replaces that with a real PTY (JNI shim over bionic forkpty) and
 * a proot sandbox so `apk add` / `apt install` work. See docs/architecture.
 */
class TerminalSessionService : Service() {

    private val sessions = LinkedHashMap<String, ShellSession>()

    /** One sandbox for the whole device — sessions boot into it once installed. */
    val sandbox: SandboxInstaller by lazy { SandboxInstaller(this) }

    inner class LocalBinder : Binder() {
        val service: TerminalSessionService get() = this@TerminalSessionService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    fun openSession(id: String, cwd: String?): ShellSession =
        sessions.getOrPut(id) { ShellSession(id, cwd, sandbox).also { it.start() } }

    /** Close and reopen a session — e.g. to enter the sandbox right after install. */
    fun restartSession(id: String, cwd: String?): ShellSession {
        sessions.remove(id)?.close()
        return openSession(id, cwd)
    }

    fun session(id: String): ShellSession? = sessions[id]

    fun sessionIds(): List<String> = sessions.keys.toList()

    fun closeSession(id: String) {
        sessions.remove(id)?.close()
        if (sessions.isEmpty()) stopSelf()
    }

    override fun onDestroy() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, KodelabApp.TERMINAL_CHANNEL_ID)
            .setContentTitle(getString(R.string.terminal_notification_title))
            .setContentText(getString(R.string.terminal_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    companion object {
        private const val NOTIFICATION_ID = 42
    }
}
