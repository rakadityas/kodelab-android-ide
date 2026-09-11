package dev.kodelab.ide

import dev.kodelab.ide.terminal.TerminalKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeysTest {

    private val esc = "\u001B"

    @Test
    fun `ctrl folds a letter into its control byte`() {
        assertEquals("\u0003", TerminalKeys.key("c", ctrl = true))
        assertEquals("\u0003", TerminalKeys.key("C", ctrl = true))
        assertEquals("\u0004", TerminalKeys.key("d", ctrl = true))
        assertEquals("\u001A", TerminalKeys.key("z", ctrl = true))
    }

    @Test
    fun `ctrl leaves keys outside the control range alone`() {
        assertEquals("1", TerminalKeys.key("1", ctrl = true))
        assertEquals("|", TerminalKeys.key("|", ctrl = true))
    }

    @Test
    fun `shift upper-cases, alt prefixes escape`() {
        assertEquals("C", TerminalKeys.key("c", shift = true))
        assertEquals(esc + "c", TerminalKeys.key("c", alt = true))
        assertEquals(esc + "\u0003", TerminalKeys.key("c", alt = true, ctrl = true))
    }

    @Test
    fun `an unmodified arrow is a bare CSI`() {
        assertEquals(esc + "[A", TerminalKeys.csi('A'))
        assertEquals(esc + "[D", TerminalKeys.csi('D'))
    }

    @Test
    fun `modified arrows carry the xterm modifier parameter`() {
        assertEquals(esc + "[1;2A", TerminalKeys.csi('A', shift = true))
        assertEquals(esc + "[1;3A", TerminalKeys.csi('A', alt = true))
        assertEquals(esc + "[1;5C", TerminalKeys.csi('C', ctrl = true))
        assertEquals(esc + "[1;8C", TerminalKeys.csi('C', shift = true, alt = true, ctrl = true))
    }

    @Test
    fun `the modifier parameter matches the xterm table`() {
        assertEquals(1, TerminalKeys.modifierParam(false, false, false))
        assertEquals(2, TerminalKeys.modifierParam(shift = true, alt = false, ctrl = false))
        assertEquals(5, TerminalKeys.modifierParam(shift = false, alt = false, ctrl = true))
        assertEquals(8, TerminalKeys.modifierParam(shift = true, alt = true, ctrl = true))
    }
}
