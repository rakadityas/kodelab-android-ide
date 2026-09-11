package dev.kodelab.ide.workspace

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the app had open when it was last closed, so a cold start lands you back
 * where you were. A window opened from inside the app deliberately ignores this
 * and starts empty.
 */
@kotlinx.serialization.Serializable
data class SessionSnapshot(
    val workspaceUri: String? = null,
    /** Open file tabs, in tab order. Virtual buffers (diffs, Welcome) aren't restored. */
    val openFiles: List<String> = emptyList(),
    val activeFile: String? = null,
)

/** Device-wide user settings — the layer beneath every workspace's presets. */
data class UserSettings(
    val themeId: String = "system",
    val fontFamily: String = "JetBrains Mono",
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kodelab_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_id")
        val FONT = stringPreferencesKey("font_family")
        val SESSION = stringPreferencesKey("session")
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

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

    /** The last session, or an empty one when there's nothing (or nothing valid) stored. */
    val session: Flow<SessionSnapshot> = context.dataStore.data.map { p ->
        p[Keys.SESSION]
            ?.let { runCatching { json.decodeFromString<SessionSnapshot>(it) }.getOrNull() }
            ?: SessionSnapshot()
    }

    suspend fun saveSession(snapshot: SessionSnapshot) {
        context.dataStore.edit {
            it[Keys.SESSION] = json.encodeToString(SessionSnapshot.serializer(), snapshot)
        }
    }
}
