package dev.kodelab.ide.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-folder presets (REQ 8). Lives at <folder>/.kodelab/workspace.json and is
 * safe to commit — a team checks it in and everyone gets the same Kodelab setup.
 *
 * Resolution order (later wins):
 *   1. [WorkspacePresets.Defaults]
 *   2. device-wide user settings (SettingsStore)
 *   3. workspace.json  (this type)
 *   4. .kodelab/state.json  (local, ephemeral, git-ignored — not modelled here)
 *
 * The terminal is intentionally NOT part of presets: it is one shared device-wide
 * service. Only a new terminal's initial working directory follows the workspace.
 */
@Serializable
data class WorkspacePresets(
    val schema: Int = 1,
    val themeId: String = "system",
    val fontFamily: String = "JetBrains Mono",
    val fontSizeSp: Int = 14,
    val lineHeight: Float = 1.6f,
    val letterSpacing: Float = 0f,
    val ligatures: Boolean = true,
    val tabWidth: Int = 4,
    val insertSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val sidebarView: String = "explorer", // explorer | search | git | extensions
    val excludeGlobs: List<String> = listOf("**/.git", "**/node_modules", "**/build"),
    val languageServers: Map<String, String> = emptyMap(), // languageId -> launch command in sandbox
) {
    companion object {
        val Defaults = WorkspacePresets()

        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun parse(text: String): WorkspacePresets =
            runCatching { json.decodeFromString<WorkspacePresets>(text) }.getOrDefault(Defaults)

        fun serialize(presets: WorkspacePresets): String =
            json.encodeToString(serializer(), presets)
    }

    /** Merge user-level overrides beneath this workspace file. */
    fun withUserDefaults(user: UserSettings): WorkspacePresets = copy(
        themeId = if (themeId == "system") user.themeId else themeId,
        fontFamily = fontFamily.ifBlank { user.fontFamily },
    )
}
