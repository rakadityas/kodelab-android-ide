package dev.kodelab.ide.workspace

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Device-wide user settings — the layer beneath every workspace's presets. */
data class UserSettings(
    val themeId: String = "kodelab-dark",
    val fontFamily: String = "JetBrains Mono",
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kodelab_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_id")
        val FONT = stringPreferencesKey("font_family")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            themeId = p[Keys.THEME] ?: UserSettings().themeId,
            fontFamily = p[Keys.FONT] ?: UserSettings().fontFamily,
        )
    }

    suspend fun setTheme(themeId: String) {
        context.dataStore.edit { it[Keys.THEME] = themeId }
    }

    suspend fun setFont(family: String) {
        context.dataStore.edit { it[Keys.FONT] = family }
    }
}
