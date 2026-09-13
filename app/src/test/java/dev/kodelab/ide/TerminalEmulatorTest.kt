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
        val e = TerminalEmulator(maxLines = 10, rows = 4, cols = 40)
        repeat(100) { e.feed("line$it\n") }
        assertTrue(e.plainText().lines().size <= 10)
    }

    // --- cursor addressing (what an interactive prompt redraws with) --------

    @Test
    fun `cursor up rewrites the line in place instead of adding one`() {
        // What `gh auth login` does to move the highlight in its option list:
        // go back up over the list and redraw it. Before the screen buffer this
        // appended a fresh copy of the list on every keypress.
        val e = emu("one\ntwo\nthree\n")
        e.feed("$esc[3A")          // back to the "one" line
        e.feed("\r$esc[K> one")
        assertEquals("> one\ntwo\nthree", e.plainText())
    }

    @Test
    fun `absolute positioning overwrites the addressed cell`() {
        val e = emu("abc\ndef")
        e.feed("$esc[1;2HX")       // row 1, col 2
        assertEquals("aXc\ndef", e.plainText())
    }

    @Test
    fun `erase in display clears the screen and the history above it`() {
        // `clear`. The view is one flat list, so leaving the scrollback behind
        // would look like nothing happened.
        val e = emu("keep\nthis\naround")
        e.feed("$esc[2J$esc[H")
        assertEquals("", e.plainText())
    }

    @Test
    fun `erase to start of line`() {
        val e = emu("abcdef")
        e.feed("$esc[3G$esc[1K")   // cursor to col 3, erase back to the start
        assertEquals("   def", e.plainText())
    }

    @Test
    fun `insert and delete characters shift the rest of the line`() {
        assertEquals("abcd", emu("acd${esc}[3D${esc}[1C${esc}[1@b").plainText().trimEnd())
        assertEquals("acd", emu("abcd${esc}[4D${esc}[1C${esc}[1P").plainText().trimEnd())
    }

    // --- queries: the replies a prompt library blocks waiting for -----------

    @Test
    fun `cursor position report is answered`() {
        val replies = StringBuilder()
        val e = TerminalEmulator(onReply = { replies.append(it) })
        e.feed("hello")
        e.feed("$esc[6n")
        assertEquals("$esc[1;6R", replies.toString())
    }

    @Test
    fun `device attributes are answered`() {
        val replies = StringBuilder()
        TerminalEmulator(onReply = { replies.append(it) }).feed("$esc[c")
        assertEquals("$esc[?1;2c", replies.toString())
    }

    @Test
    fun `background colour query is answered`() {
        val replies = StringBuilder()
        TerminalEmulator(onReply = { replies.append(it) }).feed("$esc]11;?$bel")
        assertTrue(replies.startsWith("$esc]11;rgb:"))
    }

    // --- modes --------------------------------------------------------------

    @Test
    fun `alternate screen keeps the scrollback and restores it`() {
        val e = emu("shell history\n")
        e.feed("$esc[?1049h")          // a full-screen program starts
        e.feed("full screen app")
        assertEquals("full screen app", e.plainText())
        assertTrue(e.alternateScreen)
        e.feed("$esc[?1049l")          // …and exits
        assertEquals("shell history", e.plainText().trimEnd())
        assertTrue(!e.alternateScreen)
    }

    @Test
    fun `application cursor keys mode is tracked`() {
        val e = TerminalEmulator()
        assertTrue(!e.applicationCursorKeys)
        e.feed("$esc[?1h")
        assertTrue(e.applicationCursorKeys)
        e.feed("$esc[?1l")
        assertTrue(!e.applicationCursorKeys)
    }

    @Test
    fun `hidden cursor is reported as hidden`() {
        val e = TerminalEmulator()
        e.feed("$esc[?25l")
        assertTrue(!e.cursorVisible)
        e.feed("$esc[?25h")
        assertTrue(e.cursorVisible)
    }

    @Test
    fun `text wraps at the right margin`() {
        val e = TerminalEmulator(rows = 6, cols = 5)
        e.feed("abcdefgh")
        assertEquals("abcde\nfgh", e.plainText())
    }

    @Test
    fun `scroll region recycles lines inside it`() {
        val e = TerminalEmulator(rows = 4, cols = 20)
        e.feed("$esc[1;3r")            // region = rows 1..3
        e.feed("$esc[1;1Ha\nb\nc")
        e.feed("\n")                   // scrolls the region, not the screen
        assertEquals("b\nc", e.plainText().trimEnd())
    }

    @Test
    fun `an open-url request reaches the host and prints nothing`() {
        val opened = mutableListOf<String>()
        val e = TerminalEmulator(onOpenUrl = { opened.add(it) })
        e.feed("$esc]1337;open=https://github.com/login/device$bel")
        assertEquals(listOf("https://github.com/login/device"), opened)
        assertEquals("", e.plainText())
    }

    @Test
    fun `only http urls are passed to the host`() {
        val opened = mutableListOf<String>()
        val e = TerminalEmulator(onOpenUrl = { opened.add(it) })
        e.feed("$esc]1337;open=file:///etc/passwd$bel")
        e.feed("$esc]1337;open=javascript:alert(1)$bel")
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `bracketed paste mode is tracked`() {
        val e = TerminalEmulator()
        assertTrue(!e.bracketedPaste)
        e.feed("$esc[?2004h")
        assertTrue(e.bracketedPaste)
        e.feed("$esc[?2004l")
        assertTrue(!e.bracketedPaste)
    }

    @Test
    fun `resizing smaller does not push blank lines under the prompt`() {
        // The soft keyboard opening shrinks the view, which resizes the pty.
        // Counting the screen's own padding rows as content used to move the
        // cursor a screenful down the buffer — a stack of blank lines appearing
        // below the prompt the moment the keyboard came up.
        val e = TerminalEmulator(rows = 24, cols = 80)
        e.feed("welcome\n$ ")
        val before = e.plainText()
        e.resize(8, 45)
        assertEquals(before, e.plainText())
        assertEquals(1, e.cursorRow)
        e.resize(24, 80)
        assertEquals(before, e.plainText())
        assertEquals(1, e.cursorRow)
    }

    @Test
    fun `a full screen app keeps its geometry across a resize`() {
        val e = TerminalEmulator(rows = 10, cols = 40)
        e.feed("$esc[?1049h")
        e.feed("$esc[5;1Hmiddle of the screen")
        e.resize(6, 30)
        assertTrue(e.alternateScreen)
        e.feed("$esc[?1049l")
        assertEquals("", e.plainText().trimEnd())
    }

    @Test
    fun `cursor position is exposed for rendering`() {
        val e = emu("ab\ncd")
        assertEquals(1, e.cursorRow)
        assertEquals(2, e.cursorCol)
    }
}
