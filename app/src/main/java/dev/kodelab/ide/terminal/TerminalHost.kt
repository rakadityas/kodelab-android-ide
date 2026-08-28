package dev.kodelab.ide.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide handle to the shared [TerminalSessionService] (REQ 5/8): every
 * window and every workspace talks to the same sessions, so a running build or
 * a logged-in `claude` keeps going when you switch folders or open a new window.
 */
object TerminalHost {

    private val _service = MutableStateFlow<TerminalSessionService?>(null)
    val service: StateFlow<TerminalSessionService?> = _service

    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            _service.value = (binder as TerminalSessionService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName) {
            _service.value = null
        }
    }

    /** Idempotent; call with the Application context so the binding outlives windows. */
    fun connect(context: Context) {
        if (bound) return
        val app = context.applicationContext
        val intent = Intent(app, TerminalSessionService::class.java)
        app.startForegroundService(intent)
        bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /** The default shared session, created on first use. */
    fun defaultSession(cwd: String?): ShellSession? =
        _service.value?.openSession(DEFAULT_SESSION_ID, cwd)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Download + set up the Alpine/proot sandbox, then restart the default
     * session into it. Runs on a host-level scope so it survives the terminal
     * panel being closed mid-download.
     */
    fun installSandbox() {
        val svc = _service.value ?: return
        scope.launch {
            svc.sandbox.install()
            if (svc.sandbox.isInstalled) {
                svc.restartSession(DEFAULT_SESSION_ID, null)
            }
        }
    }

    private const val DEFAULT_SESSION_ID = "main"
}
