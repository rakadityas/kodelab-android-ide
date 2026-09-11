package dev.kodelab.ide

import dev.kodelab.ide.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SGR attributes the renderer needs in order not to look flat. */
class TerminalSgrTest {

    private val esc = "\u001B"

    private fun styleOf(input: String): TerminalEmulator.CellStyle {
        val e = TerminalEmulator()
        e.feed(input.replace("<E>", esc))
        return e.render().first().first { it.text.isNotBlank() }.style
    }

    @Test
    fun `truecolour foreground is kept, not discarded`() {
        val style = styleOf("<E>[38;2;64;128;192mx")
        assertTrue(style.fg and TerminalEmulator.TRUECOLOR != 0)
        assertEquals(0x4080C0, style.fg and 0xFFFFFF)
    }

    @Test
    fun `truecolour background is kept too`() {
        val style = styleOf("<E>[48;2;10;20;30mx")
        assertEquals(0x0A141E, style.bg and 0xFFFFFF)
    }

    @Test
    fun `256-colour indices still work alongside truecolour`() {
        assertEquals(200, styleOf("<E>[38;5;200mx").fg)
    }

    @Test
    fun `background colours are recorded`() {
        assertEquals(4, styleOf("<E>[44mx").bg)
        assertEquals(12, styleOf("<E>[104mx").bg)
    }

    @Test
    fun `dim and italic are parsed and reset`() {
        assertTrue(styleOf("<E>[2mx").dim)
        assertTrue(styleOf("<E>[3mx").italic)
        assertTrue(!styleOf("<E>[2m<E>[22mx").dim)
        assertTrue(!styleOf("<E>[3m<E>[23mx").italic)
    }

    @Test
    fun `reset clears every attribute`() {
        val style = styleOf("<E>[1;3;4;7;38;2;1;2;3m<E>[0mx")
        assertEquals(TerminalEmulator.CellStyle(), style)
    }
}
