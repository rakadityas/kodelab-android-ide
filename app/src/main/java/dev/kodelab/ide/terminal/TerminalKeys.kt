package dev.kodelab.ide.terminal

/**
 * Folding the soft keyboard's missing modifiers into the bytes a terminal
 * expects. Kept out of the UI so it can be tested: a wrong control byte looks
 * like "the button does nothing", which is hard to spot by eye.
 */
object TerminalKeys {

    const val ESC = "\u001B"

    /** xterm's modifier parameter: 1 + shift(1) + alt(2) + ctrl(4). */
    fun modifierParam(shift: Boolean, alt: Boolean, ctrl: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    /** An arrow/navigation key, carrying whichever modifiers are armed. */
    fun csi(final: Char, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        val m = modifierParam(shift, alt, ctrl)
        return if (m == 1) "$ESC[$final" else "$ESC[1;$m$final"
    }

    /**
     * An arrow key in the form the program on the other end expects. A
     * full-screen program that has set DECCKM (`ESC[?1h` — vim, less, and the
     * list prompts in `gh`) reads `ESC O A`, and ignores the `ESC [ A` a plain
     * shell wants, which is why an unqualified arrow looked like it did nothing
     * there. Modifiers always use the CSI form, as xterm does.
     */
    fun cursorKey(
        final: Char,
        shift: Boolean = false,
        alt: Boolean = false,
        ctrl: Boolean = false,
        applicationMode: Boolean = false,
    ): String =
        if (applicationMode && modifierParam(shift, alt, ctrl) == 1) "${ESC}O$final"
        else csi(final, shift, alt, ctrl)

    /**
     * A printable key with the armed modifiers applied: shift upper-cases it,
     * ctrl turns @.._ into the matching control byte (so c becomes 0x03), and
     * alt prefixes ESC — the same thing a real terminal sends.
     */
    fun key(ch: String, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        var base = ch
        if (shift && base.length == 1) base = base[0].uppercaseChar().toString()
        if (ctrl && base.length == 1 && base[0].uppercaseChar() in '@'..'_') {
            base = (base[0].uppercaseChar().code - 64).toChar().toString()
        }
        return if (alt) ESC + base else base
    }
}
