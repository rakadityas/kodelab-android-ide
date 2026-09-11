package dev.kodelab.ide

import androidx.compose.ui.graphics.Color
import dev.kodelab.ide.terminal.TerminalEmulator
import dev.kodelab.ide.terminal.TerminalSchemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSchemeTest {

    private val scheme = TerminalSchemes.Midnight

    @Test
    fun `every scheme has all sixteen ansi slots`() {
        TerminalSchemes.all.forEach { assertEquals(it.name, 16, it.ansi.size) }
    }

    @Test
    fun `palette indices map to the scheme's own colours`() {
        assertEquals(scheme.ansi[1], scheme.colorFor(1))
        assertEquals(scheme.ansi[15], scheme.colorFor(15))
    }

    @Test
    fun `truecolour slots decode to their rgb`() {
        val slot = TerminalEmulator.TRUECOLOR or (0x40 shl 16) or (0x80 shl 8) or 0xC0
        assertEquals(Color(0xFF4080C0), scheme.colorFor(slot))
    }

    @Test
    fun `the 216-colour cube and greyscale ramp stay in range`() {
        assertEquals(Color(0, 0, 0), scheme.colorFor(16))
        assertEquals(Color(255, 255, 255), scheme.colorFor(231))
        val grey = scheme.colorFor(240)
        assertEquals(grey.red, grey.blue, 0.001f)
    }

    @Test
    fun `an unset slot has no colour of its own`() {
        assertEquals(Color.Unspecified, scheme.colorFor(-1))
    }

    @Test
    fun `unknown ids fall back to the theme, and auto follows it`() {
        val dark = dev.kodelab.ide.theme.KodelabThemes.dark
        val light = dev.kodelab.ide.theme.KodelabThemes.light
        assertTrue(TerminalSchemes.resolve("no-such-scheme", dark).isDark)
        assertTrue(!TerminalSchemes.resolve(TerminalSchemes.AUTO, light).isDark)
        assertEquals(dark.surface, TerminalSchemes.resolve(TerminalSchemes.AUTO, dark).background)
    }

    @Test
    fun `the dark theme is true black, and the terminal follows it`() {
        val dark = dev.kodelab.ide.theme.KodelabThemes.dark
        // AMOLED: the editor's own background is off-pixels, not dark grey
        assertEquals(Color(0xFF000000), dark.surface)
        assertEquals(Color(0xFF000000), dark.tabActive)
        assertEquals(Color(0xFF000000), TerminalSchemes.resolve(TerminalSchemes.AUTO, dark).background)
    }

    @Test
    fun `named schemes ignore the editor theme`() {
        val light = dev.kodelab.ide.theme.KodelabThemes.light
        val ember = TerminalSchemes.resolve("ember", light)
        assertEquals(TerminalSchemes.Ember.background, ember.background)
        assertNotEquals(light.surface, ember.background)
    }
}
