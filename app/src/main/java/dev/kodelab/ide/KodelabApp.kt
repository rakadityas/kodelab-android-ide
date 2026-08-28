package dev.kodelab.ide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.kodelab.ide.workspace.SettingsStore

class KodelabApp : Application() {

    val settings: SettingsStore by lazy { SettingsStore(this) }

    override fun onCreate() {
        super.onCreate()
        createTerminalChannel()
    }

    private fun createTerminalChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            TERMINAL_CHANNEL_ID,
            getString(R.string.terminal_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        const val TERMINAL_CHANNEL_ID = "terminal_sessions"
    }
}
