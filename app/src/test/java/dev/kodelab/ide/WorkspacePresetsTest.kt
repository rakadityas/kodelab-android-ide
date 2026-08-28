package dev.kodelab.ide

import dev.kodelab.ide.workspace.UserSettings
import dev.kodelab.ide.workspace.WorkspacePresets
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspacePresetsTest {

    @Test
    fun `round trips through json`() {
        val presets = WorkspacePresets(themeId = "kodelab-light", fontSizeSp = 16, wordWrap = true)
        val back = WorkspacePresets.parse(WorkspacePresets.serialize(presets))
        assertEquals(presets, back)
    }

    @Test
    fun `garbage input falls back to defaults`() {
        assertEquals(WorkspacePresets.Defaults, WorkspacePresets.parse("not json at all"))
        assertEquals(WorkspacePresets.Defaults, WorkspacePresets.parse(""))
    }

    @Test
    fun `unknown keys are ignored for forward compatibility`() {
        val parsed = WorkspacePresets.parse("""{"schema":1,"themeId":"kodelab-dark","futureKnob":42}""")
        assertEquals("kodelab-dark", parsed.themeId)
    }

    @Test
    fun `system theme defers to user settings`() {
        val ws = WorkspacePresets(themeId = "system")
        val merged = ws.withUserDefaults(UserSettings(themeId = "kodelab-light"))
        assertEquals("kodelab-light", merged.themeId)
    }

    @Test
    fun `explicit workspace theme beats user settings`() {
        val ws = WorkspacePresets(themeId = "kodelab-dark")
        val merged = ws.withUserDefaults(UserSettings(themeId = "kodelab-light"))
        assertEquals("kodelab-dark", merged.themeId)
    }
}
