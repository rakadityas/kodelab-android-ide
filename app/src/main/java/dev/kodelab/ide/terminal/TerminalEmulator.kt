package dev.kodelab.ide.terminal

/**
 * A small terminal emulator: enough of VT/ANSI to render a shell, `apk`, `git`
 * and friends legibly in a plain scrolling view. It is NOT a full screen buffer
 * (no cursor addressing / alternate screen) — it keeps a list of styled lines and
 * handles the escapes that ordinary command-line tools actually emit:
 *
 *  - `\n` new line, `\r` carriage return (overwrite from column 0 — this is what
 *    makes progress bars redraw in place instead of stacking up),
 *  - `\b` backspace, `\t` tab (to 8-col stops),
 *  - SGR (`ESC[...m`) colours / bold / reverse / underline,
 *  - `ESC[K` erase-to-end-of-line, and assorted CSI/OSC sequences that are simply
 *    consumed so they don't show as garbage.
 *
 * Pure and synchronous so it can be unit-tested without Android.
 */
class TerminalEmulator(private val maxLines: Int = 5000) {

    /** One run of text sharing a single style. */
    data class Span(val text: String, val style: CellStyle)

    data class CellStyle(
        val fg: Int = -1,      // -1 == default; 0..255 palette index
        val bg: Int = -1,
        val bold: Boolean = false,
        val underline: Boolean = false,
        val reverse: Boolean = false,
    )

    private class Line {
        val cells = ArrayList<Char>()
        val styles = ArrayList<CellStyle>()
        fun putAt(col: Int, ch: Char, style: CellStyle) {
            while (cells.size <= col) { cells.add(' '); styles.add(CellStyle()) }
            cells[col] = ch; styles[col] = style
        }
        fun eraseToEnd(col: Int) {
            while (cells.size > col) {
                cells.removeAt(cells.size - 1); styles.removeAt(styles.size - 1)
            }
        }
    }

    private val lines = ArrayList<Line>().apply { add(Line()) }
    private var col = 0
    private var style = CellStyle()

    private enum class Mode { TEXT, ESC, ESC_ARG, CSI, OSC, OSC_ESC }
    private var mode = Mode.TEXT
    private val seq = StringBuilder()

    /** Feed raw text decoded from the pty. */
    fun feed(data: String) {
        for (c in data) {
            when (mode) {
                Mode.TEXT -> onText(c)
                Mode.ESC -> onEsc(c)
                Mode.ESC_ARG -> mode = Mode.TEXT   // charset id byte after ESC( / ESC)
                Mode.CSI -> onCsi(c)
                Mode.OSC -> onOsc(c)
                Mode.OSC_ESC -> mode = Mode.TEXT   // the '\' of an ESC \ terminator
            }
        }
        trim()
    }

    private fun cur(): Line = lines.last()

    private fun onText(c: Char) {
        when (c) {
            ESC -> { mode = Mode.ESC; seq.setLength(0) }
            '\n' -> { lines.add(Line()); col = 0 }
            '\r' -> col = 0
            '\b' -> if (col > 0) col--
            '\t' -> col = (col / 8 + 1) * 8
            BEL -> {} // bell
            else -> if (c >= ' ') { cur().putAt(col, c, style); col++ }
        }
    }

    private fun onEsc(c: Char) {
        when (c) {
            '[' -> { mode = Mode.CSI; seq.setLength(0) }
            ']' -> { mode = Mode.OSC; seq.setLength(0) }
            '(', ')' -> mode = Mode.ESC_ARG
            else -> mode = Mode.TEXT
        }
    }

    private fun onCsi(c: Char) {
        if (c in '0'..'9' || c == ';' || c == '?') { seq.append(c); return }
        when (c) {
            'm' -> applySgr(seq.toString())
            'K' -> if (seq.toString().let { it.isEmpty() || it == "0" }) cur().eraseToEnd(col)
            // cursor moves we can't honour in a linear buffer: consume and ignore
            else -> {}
        }
        mode = Mode.TEXT
    }

    private fun onOsc(c: Char) {
        // OSC ... terminated by BEL or ST (ESC \).
        when (c) {
            BEL -> mode = Mode.TEXT
            ESC -> mode = Mode.OSC_ESC
            else -> {} // swallow the payload
        }
    }

    private fun applySgr(params: String) {
        val codes = if (params.isEmpty()) listOf(0)
        else params.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < codes.size) {
            when (val n = codes[i]) {
                0 -> style = CellStyle()
                1 -> style = style.copy(bold = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(reverse = true)
                22 -> style = style.copy(bold = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(reverse = false)
                in 30..37 -> style = style.copy(fg = n - 30)
                39 -> style = style.copy(fg = -1)
                in 40..47 -> style = style.copy(bg = n - 40)
                49 -> style = style.copy(bg = -1)
                in 90..97 -> style = style.copy(fg = n - 90 + 8)
                in 100..107 -> style = style.copy(bg = n - 100 + 8)
                38, 48 -> {
                    val isFg = n == 38
                    when (codes.getOrNull(i + 1)) {
                        5 -> {
                            val p = codes.getOrNull(i + 2) ?: 0
                            style = if (isFg) style.copy(fg = p) else style.copy(bg = p)
                            i += 2
                        }
                        2 -> i += 4 // truecolour: consume r;g;b, keep current
                        else -> {}
                    }
                }
                else -> {}
            }
            i++
        }
    }

    private fun trim() {
        while (lines.size > maxLines) lines.removeAt(0)
    }

    /** Snapshot the buffer as styled spans, one list per line. */
    fun render(): List<List<Span>> = lines.map { line ->
        if (line.cells.isEmpty()) return@map emptyList()
        val out = ArrayList<Span>()
        val sb = StringBuilder()
        var runStyle = line.styles[0]
        for (idx in line.cells.indices) {
            val s = line.styles[idx]
            if (s != runStyle && sb.isNotEmpty()) {
                out.add(Span(sb.toString(), runStyle)); sb.setLength(0)
            }
            runStyle = s
            sb.append(line.cells[idx])
        }
        if (sb.isNotEmpty()) out.add(Span(sb.toString(), runStyle))
        out
    }

    /** Plain text (styling dropped) — handy for tests and search. */
    fun plainText(): String = lines.joinToString("\n") { String(it.cells.toCharArray()) }

    private companion object {
        const val ESC = '\u001B'
        const val BEL = '\u0007'
    }
}
