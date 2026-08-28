package dev.kodelab.ide

import dev.kodelab.ide.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    private val esc = '\u001B'
    private val bel = '\u0007'

    private fun emu(s: String) = TerminalEmulator().apply { feed(s) }

    @Test
    fun `plain text and newlines`() {
        assertEquals("hello\nworld", emu("hello\nworld").plainText())
    }

    @Test
    fun `carriage return overwrites the line in place`() {
        // progress-bar style: "50%\r100%" reads as "100%", not two lines
        assertEquals("100%", emu("50%\r100%").plainText())
    }

    @Test
    fun `shorter overwrite leaves the tail unless erased`() {
        assertEquals("12cd", emu("abcd\r12").plainText())
        // ESC[K after the CR clears to end of line
        assertEquals("12", emu("abcd\r12${esc}[K").plainText())
    }

    @Test
    fun `backspace moves the cursor back`() {
        // type "ab", backspace, then "c" overwrites the 'b'
        assertEquals("ac", emu("ab\bc").plainText())
    }

    @Test
    fun `tab advances to the next 8 column stop`() {
        assertEquals("ab      x", emu("ab\tx").plainText())
    }

    @Test
    fun `sgr colour is captured as style and dropped from text`() {
        val e = emu("${esc}[31mRED${esc}[0m done")
        assertEquals("RED done", e.plainText())
        val spans = e.render().first()
        assertEquals(1, spans.first().style.fg)          // 31 -> red (index 1)
        assertTrue(spans.any { it.style.fg == -1 })      // " done" is default
    }

    @Test
    fun `bright colours and reset`() {
        val spans = emu("${esc}[92mA${esc}[0mB").render().first()
        assertEquals(10, spans.first().style.fg)         // 92 -> bright green (8+2)
        assertEquals(-1, spans.last().style.fg)
    }

    @Test
    fun `256 colour extended sequence`() {
        assertEquals(208, emu("${esc}[38;5;208mX").render().first().first().style.fg)
    }

    @Test
    fun `bold flag`() {
        assertTrue(emu("${esc}[1mX").render().first().first().style.bold)
    }

    @Test
    fun `osc title sequence is swallowed`() {
        assertEquals("hi", emu("${esc}]0;my window title${bel}hi").plainText())
    }

    @Test
    fun `unhandled cursor moves are consumed not printed`() {
        assertEquals("done", emu("${esc}[1;1Hdone").plainText())
        assertEquals("AB", emu("A${esc}[2CB").plainText().replace(" ", ""))
    }

    @Test
    fun `scrollback is bounded`() {
        val e = TerminalEmulator(maxLines = 10)
        repeat(100) { e.feed("line$it\n") }
        assertTrue(e.plainText().lines().size <= 10)
    }
}
