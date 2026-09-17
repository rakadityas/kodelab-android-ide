package dev.kodelab.ide

import dev.kodelab.ide.ui.hitWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The match in a search result has to stay on screen. The row is one line high
 * and clipped at the panel's edge, so a hit far along a long line used to be cut
 * off the right-hand side — which is most "find references" hits, since a
 * reference is rarely in the first column.
 *
 * `hitWindow` is the arithmetic that keeps it visible, and the thing worth
 * testing about it is that `start`/`end` still bracket the matched text after
 * the leading indent and the run-up have been dropped.
 */
class SearchHitWindowTest {

    /** The matched substring, as the UI would slice it out of the window. */
    private fun matched(text: String, start: Int, end: Int): String =
        hitWindow(text, start, end).let { it.text.substring(it.start, it.end) }

    @Test
    fun `keeps the match when the line is short`() {
        val line = "val user = findUser()"
        assertEquals("findUser", matched(line, 11, 19))
        assertFalse(hitWindow(line, 11, 19).elided)
    }

    @Test
    fun `drops the indent and shifts the offsets with it`() {
        val line = "        val user = findUser()"
        val w = hitWindow(line, 19, 27)
        assertEquals("val user = findUser()", w.text)
        assertEquals("findUser", w.text.substring(w.start, w.end))
        assertFalse(w.elided)
    }

    @Test
    fun `winds a far-along match into view and says so`() {
        val line = "        return repository.resolve(session).let { it.findUser(id) }"
        val at = line.indexOf("findUser")
        val w = hitWindow(line, at, at + "findUser".length)
        assertTrue("a match this far along must be wound forward", w.elided)
        assertEquals("findUser", w.text.substring(w.start, w.end))
        // The run-up is bounded, so the match is always near the left edge.
        assertEquals(14, w.start)
    }

    @Test
    fun `handles a match at the very start and the very end`() {
        val line = "findUser(id)"
        assertEquals("findUser", matched(line, 0, 8))
        assertEquals("(id)", matched(line, 8, 12))
    }

    @Test
    fun `a tab-indented line is trimmed like a space-indented one`() {
        val line = "\t\tval x = 1"   // two tabs, so 'x' sits at index 6
        val w = hitWindow(line, 6, 7)
        assertEquals("val x = 1", w.text)
        assertEquals("x", w.text.substring(w.start, w.end))
    }

    /** Offsets out of step with the text must clamp, never throw. */
    @Test
    fun `survives offsets past the end of the line`() {
        assertEquals("", matched("    short", 200, 400))
        assertEquals("", matched("", 0, 5))
        assertEquals("", matched("       ", 3, 99))
    }
}
