package dev.kodelab.ide

import dev.kodelab.ide.terminal.ShellSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellSessionTest {

    @Test
    fun `strips csi color and cursor sequences`() {
        assertEquals("hello", ShellSession.stripAnsi("[31mhello[0m"))
        assertEquals("ab", ShellSession.stripAnsi("a[2J[Hb"))
    }

    @Test
    fun `strips osc title sequences`() {
        assertEquals("x", ShellSession.stripAnsi("]0;window titlex"))
    }

    @Test
    fun `normalizes carriage returns`() {
        assertEquals("a\nb\n", ShellSession.stripAnsi("a\r\nb\r"))
    }

    @Test
    fun `plain text passes through`() {
        assertEquals("$ ls -la\ntotal 0\n", ShellSession.stripAnsi("$ ls -la\ntotal 0\n"))
    }
}
