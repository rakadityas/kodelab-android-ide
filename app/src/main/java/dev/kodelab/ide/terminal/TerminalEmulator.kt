package dev.kodelab.ide.terminal

/**
 * A VT100/xterm-compatible terminal emulator: a real screen buffer with cursor
 * addressing, scroll regions, an alternate screen and query replies — enough for
 * the full-screen and redraw-in-place programs a developer actually runs
 * (`gh auth login`'s option lists, `vim`, `less`, `htop`, `claude`).
 *
 * The previous version was append-only: it consumed cursor moves instead of
 * honouring them, so every redraw of an interactive list stacked up as fresh
 * lines, and a program that asked the terminal where the cursor was
 * (`ESC[6n` — what Go's survey/termenv do before drawing a prompt) waited for a
 * reply that never came and hung. Both are fixed here: moves are applied, and
 * [onReply] carries answers back to the pty.
 *
 * The model is scrollback + screen in one list: the last [rows] entries are the
 * screen, everything before them is history. That keeps [render] the same
 * flat list of styled lines the UI already draws, with its own scrollback,
 * while the cursor arithmetic below works on screen coordinates.
 *
 * Pure and synchronous so it can be unit-tested without Android.
 */
class TerminalEmulator(
    private val maxLines: Int = 5000,
    rows: Int = 24,
    cols: Int = 80,
    /** Answers to terminal queries (DSR/DA/OSC colour), written back to the pty. */
    private val onReply: (String) -> Unit = {},
    /** A URL the guest asked the host to open (see [OSC_OPEN_URL]). */
    private val onOpenUrl: (String) -> Unit = {},
) {

    /** One run of text sharing a single style. */
    data class Span(val text: String, val style: CellStyle)

    /**
     * A colour slot: -1 default, 0..255 a palette index, or an RGB value with
     * [TRUECOLOR] set (24-bit `ESC[38;2;r;g;b` — what modern prompts, bat and
     * delta emit; dropping it is what makes a terminal look flat).
     */
    data class CellStyle(
        val fg: Int = -1,
        val bg: Int = -1,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val reverse: Boolean = false,
    )

    private class Line {
        val cells = ArrayList<Char>()
        val styles = ArrayList<CellStyle>()

        fun putAt(col: Int, ch: Char, style: CellStyle) {
            pad(col)
            cells[col] = ch
            styles[col] = style
        }

        /** Grow with blanks so [col] is addressable. */
        fun pad(col: Int) {
            while (cells.size <= col) { cells.add(' '); styles.add(CellStyle()) }
        }

        fun eraseToEnd(col: Int) {
            while (cells.size > col) {
                cells.removeAt(cells.size - 1); styles.removeAt(styles.size - 1)
            }
        }

        fun eraseToStart(col: Int) {
            for (i in 0..minOf(col, cells.size - 1)) { cells[i] = ' '; styles[i] = CellStyle() }
        }

        fun clear() { cells.clear(); styles.clear() }

        /** Erase [n] cells from [col] without shortening the line (ECH). */
        fun eraseCells(col: Int, n: Int) {
            for (i in col until minOf(col + n, cells.size)) {
                cells[i] = ' '; styles[i] = CellStyle()
            }
        }

        /** Shift the tail right, dropping what falls off the screen (ICH). */
        fun insertBlanks(col: Int, n: Int, cols: Int) {
            if (col >= cols) return
            pad(col)
            repeat(n) {
                cells.add(col, ' '); styles.add(col, CellStyle())
            }
            while (cells.size > cols) {
                cells.removeAt(cells.size - 1); styles.removeAt(styles.size - 1)
            }
        }

        /** Shift the tail left over [n] cells (DCH). */
        fun deleteChars(col: Int, n: Int) {
            repeat(n) {
                if (col < cells.size) { cells.removeAt(col); styles.removeAt(col) }
            }
        }

        val blank: Boolean get() = cells.isEmpty()
    }

    var rows: Int = rows.coerceAtLeast(1)
        private set
    var cols: Int = cols.coerceAtLeast(1)
        private set

    /** Scrollback followed by the screen; the last [rows] entries are the screen. */
    private var lines = ArrayList<Line>()
    /** Index in [lines] of screen row 0. Invariant: lines.size == screenTop + rows. */
    private var screenTop = 0

    private var row = 0
    private var col = 0
    private var style = CellStyle()
    /** Set once a glyph lands in the last column: the wrap happens on the next one. */
    private var pendingWrap = false
    private var autoWrap = true

    // Scroll region (DECSTBM), screen-relative and inclusive — vim/less set one.
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private var saved: Cursor? = null
    private var altSaved: Alt? = null

    /** DECTCEM — false while a program hides the cursor mid-redraw. */
    var cursorVisible = true
        private set

    /**
     * DECCKM. While set, arrow keys must be sent as `ESC O A` rather than
     * `ESC [ A` — which is why the key bar asks before sending one.
     */
    var applicationCursorKeys = false
        private set

    /** True while a full-screen program (vim, less, htop) owns the screen. */
    val alternateScreen: Boolean get() = altSaved != null

    private data class Cursor(val row: Int, val col: Int, val style: CellStyle)
    private class Alt(
        val lines: ArrayList<Line>,
        val screenTop: Int,
        val cursor: Cursor,
        val scrollTop: Int,
        val scrollBottom: Int,
    )

    private enum class Mode { TEXT, ESC, ESC_ARG, CSI, OSC, OSC_ESC, DCS, DCS_ESC }
    private var mode = Mode.TEXT
    private val seq = StringBuilder()

    init {
        repeat(this.rows) { lines.add(Line()) }
    }

    // ---------- input ----------

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
                Mode.DCS -> if (c == ESC) mode = Mode.DCS_ESC else if (c == BEL) mode = Mode.TEXT
                Mode.DCS_ESC -> mode = Mode.TEXT
            }
        }
        trim()
    }

    private fun onText(c: Char) {
        when (c) {
            ESC -> { mode = Mode.ESC; seq.setLength(0) }
            // LF returns to column 0 as well as moving down (LNM). The pty's
            // line discipline already turns a program's "\n" into "\r\n", and the
            // banners this class is fed directly use a bare "\n" — treating LF
            // as a plain index would staircase both down the screen.
            '\n', '\u000B', '\u000C' -> { col = 0; pendingWrap = false; lineFeed() }
            '\r' -> { col = 0; pendingWrap = false }
            '\b' -> { if (col > 0) col--; pendingWrap = false }
            '\t' -> { col = minOf(cols - 1, (col / 8 + 1) * 8); pendingWrap = false }
            BEL -> {}
            else -> if (c >= ' ') printChar(c)
        }
    }

    private fun printChar(c: Char) {
        if (pendingWrap) {
            col = 0
            lineFeed()
            pendingWrap = false
        }
        lineAt(row).putAt(col, c, style)
        if (col + 1 >= cols) {
            if (autoWrap) pendingWrap = true
        } else {
            col++
        }
    }

    private fun onEsc(c: Char) {
        when (c) {
            '[' -> { mode = Mode.CSI; seq.setLength(0); return }
            ']' -> { mode = Mode.OSC; seq.setLength(0); return }
            'P', '_', '^' -> { mode = Mode.DCS; return }  // DCS/APC/PM ... ST
            '(', ')', '*', '+', '#', '%' -> { mode = Mode.ESC_ARG; return }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()                       // IND
            'E' -> { col = 0; lineFeed() }           // NEL
            'M' -> reverseIndex()                    // RI
            'c' -> reset()                           // RIS
            else -> {}                               // keypad modes etc.
        }
        mode = Mode.TEXT
    }

    private fun onOsc(c: Char) {
        // OSC <payload> terminated by BEL or ST (ESC \).
        when (c) {
            BEL -> { dispatchOsc(seq.toString()); mode = Mode.TEXT }
            ESC -> { dispatchOsc(seq.toString()); mode = Mode.OSC_ESC }
            else -> seq.append(c)
        }
    }

    /**
     * Colour queries, and the host's own "open this URL" request.
     *
     * `ESC]11;?` ("what is your background?") is what termenv — and so every
     * Charm/Bubbletea program — sends to decide on a light or dark palette;
     * with no answer it blocks until its own timeout. [OSC_OPEN_URL] is ours:
     * the guest has no browser, so the `xdg-open` shim in the sandbox prints
     * this and the app opens the URL on the phone.
     */
    private fun dispatchOsc(payload: String) {
        val code = payload.substringBefore(';')
        val arg = payload.substringAfter(';', "")
        if (code == OSC_OPEN_URL) {
            val url = arg.removePrefix("open=").trim()
            if (url.startsWith("http://") || url.startsWith("https://")) onOpenUrl(url)
            return
        }
        if (!arg.startsWith("?")) return
        when (code) {
            "10" -> onReply("$ESC]10;rgb:e7e7/eded/eeee$ESC\\")
            "11" -> onReply("$ESC]11;rgb:1111/1717/1a1a$ESC\\")
        }
    }

    private fun onCsi(c: Char) {
        // Parameter and intermediate bytes (0x20..0x3F) accumulate; the first
        // byte in 0x40..0x7E ends the sequence.
        if (c.code in 0x20..0x3F) { seq.append(c); return }
        dispatchCsi(c, seq.toString())
        mode = Mode.TEXT
    }

    private fun dispatchCsi(final: Char, raw: String) {
        val private = raw.firstOrNull()?.takeIf { it in "?<>=" }
        val body = if (private != null) raw.drop(1) else raw
        val params = body.split(';').map { it.trim().toIntOrNull() }
        // A cursor-move parameter of 0 means 1; an absent one means 1 too.
        fun n(i: Int) = (params.getOrNull(i) ?: 0).coerceAtLeast(1)
        fun at(i: Int, def: Int) = params.getOrNull(i) ?: def

        if (private == '?' && (final == 'h' || final == 'l')) {
            params.filterNotNull().forEach { setPrivateMode(it, final == 'h') }
            return
        }

        when (final) {
            'A' -> { row = maxOf(topLimit(), row - n(0)); pendingWrap = false }
            'B' -> { row = minOf(bottomLimit(), row + n(0)); pendingWrap = false }
            'C' -> { col = minOf(cols - 1, col + n(0)); pendingWrap = false }
            'D' -> { col = maxOf(0, col - n(0)); pendingWrap = false }
            'E' -> { row = minOf(bottomLimit(), row + n(0)); col = 0; pendingWrap = false }
            'F' -> { row = maxOf(topLimit(), row - n(0)); col = 0; pendingWrap = false }
            'G', '`' -> { col = (n(0) - 1).coerceIn(0, cols - 1); pendingWrap = false }
            'd' -> { row = (n(0) - 1).coerceIn(0, rows - 1); pendingWrap = false }
            'H', 'f' -> {
                row = (n(0) - 1).coerceIn(0, rows - 1)
                col = (n(1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'J' -> eraseInDisplay(at(0, 0))
            'K' -> eraseInLine(at(0, 0))
            'L' -> insertLines(n(0))
            'M' -> deleteLines(n(0))
            'P' -> lineAt(row).deleteChars(col, n(0))
            '@' -> lineAt(row).insertBlanks(col, n(0), cols)
            'X' -> lineAt(row).eraseCells(col, n(0))
            'S' -> scrollUp(n(0))
            'T' -> scrollDown(n(0))
            'r' -> {
                val top = (at(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (at(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) { scrollTop = top; scrollBottom = bottom }
                row = scrollTop; col = 0
            }
            'm' -> applySgr(body)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> when (at(0, 0)) {
                // "Where is the cursor?" — the query a prompt library blocks on.
                6 -> onReply("$ESC[${row + 1};${col + 1}R")
                5 -> onReply("$ESC[0n")
            }
            'c' -> when (private) {
                '>' -> onReply("$ESC[>0;10;1c")
                else -> onReply("$ESC[?1;2c")   // "a VT100 with advanced video"
            }
            't' -> if (at(0, 0) == 18) onReply("$ESC[8;$rows;${cols}t")
            else -> {}
        }
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> applicationCursorKeys = on
            7 -> autoWrap = on
            25 -> cursorVisible = on
            47, 1047, 1049 -> setAlternateScreen(on)
            2004 -> bracketedPaste = on
            else -> {}  // mouse reporting, …
        }
    }

    /**
     * DECSET 2004. A program that asks for bracketed paste wants pasted text
     * wrapped in markers so it can tell it from typing — without them an editor
     * treats every pasted newline as "run this".
     */
    var bracketedPaste = false
        private set

    // ---------- screen ----------

    private fun lineAt(r: Int): Line {
        ensureScreen()
        return lines[screenTop + r.coerceIn(0, rows - 1)]
    }

    private fun ensureScreen() {
        while (lines.size < screenTop + rows) lines.add(Line())
    }

    /** Cursor moves stay inside the scroll region when it already contains them. */
    private fun topLimit() = if (row >= scrollTop) scrollTop else 0
    private fun bottomLimit() = if (row <= scrollBottom) scrollBottom else rows - 1

    private fun lineFeed() {
        if (row >= scrollBottom) scrollUp(1) else row++
    }

    private fun reverseIndex() {
        if (row <= scrollTop) scrollDown(1) else row--
    }

    /**
     * Scroll the region up by [n]. When the region is the whole screen of the
     * normal buffer the top line moves into scrollback — that is what makes
     * history accumulate; inside a region (or on the alternate screen) the
     * lines are simply recycled.
     */
    private fun scrollUp(n: Int) {
        val count = n.coerceIn(1, rows)
        ensureScreen()
        if (scrollTop == 0 && scrollBottom == rows - 1 && altSaved == null) {
            repeat(count) { lines.add(Line()); screenTop++ }
            return
        }
        repeat(count) {
            lines.removeAt(screenTop + scrollTop)
            lines.add(screenTop + scrollBottom, Line())
        }
    }

    private fun scrollDown(n: Int) {
        val count = n.coerceIn(1, rows)
        ensureScreen()
        repeat(count) {
            lines.removeAt(screenTop + scrollBottom)
            lines.add(screenTop + scrollTop, Line())
        }
    }

    private fun insertLines(n: Int) {
        if (row < scrollTop || row > scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - row + 1)
        ensureScreen()
        repeat(count) {
            lines.removeAt(screenTop + scrollBottom)
            lines.add(screenTop + row, Line())
        }
        col = 0
    }

    private fun deleteLines(n: Int) {
        if (row < scrollTop || row > scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - row + 1)
        ensureScreen()
        repeat(count) {
            lines.removeAt(screenTop + row)
            lines.add(screenTop + scrollBottom, Line())
        }
        col = 0
    }

    private fun eraseInLine(what: Int) {
        val line = lineAt(row)
        when (what) {
            0 -> line.eraseToEnd(col)
            1 -> line.eraseToStart(col)
            2 -> line.clear()
        }
    }

    private fun eraseInDisplay(what: Int) {
        ensureScreen()
        when (what) {
            0 -> {
                lineAt(row).eraseToEnd(col)
                for (r in row + 1 until rows) lineAt(r).clear()
            }
            1 -> {
                lineAt(row).eraseToStart(col)
                for (r in 0 until row) lineAt(r).clear()
            }
            // ED 2 erases the screen and ED 3 the scrollback with it. Both drop
            // the scrollback here: the view is one continuous list with no
            // screen boundary drawn in it, so leaving the history in place
            // would make `clear` look like it had done nothing. On the
            // alternate screen there is no scrollback to drop.
            2, 3 -> {
                for (r in 0 until rows) lineAt(r).clear()
                repeat(screenTop) { lines.removeAt(0) }
                screenTop = 0
            }
        }
    }

    private fun setAlternateScreen(on: Boolean) {
        if (on == (altSaved != null)) return
        if (on) {
            altSaved = Alt(lines, screenTop, Cursor(row, col, style), scrollTop, scrollBottom)
            lines = ArrayList<Line>().apply { repeat(rows) { add(Line()) } }
            screenTop = 0
            row = 0; col = 0
            scrollTop = 0; scrollBottom = rows - 1
        } else {
            val a = altSaved ?: return
            altSaved = null
            lines = a.lines
            screenTop = a.screenTop
            row = a.cursor.row.coerceIn(0, rows - 1)
            col = a.cursor.col.coerceIn(0, cols - 1)
            style = a.cursor.style
            scrollTop = a.scrollTop.coerceIn(0, rows - 1)
            scrollBottom = a.scrollBottom.coerceIn(scrollTop, rows - 1)
            ensureScreen()
        }
        pendingWrap = false
    }

    private fun saveCursor() { saved = Cursor(row, col, style) }

    private fun restoreCursor() {
        val c = saved ?: return
        row = c.row.coerceIn(0, rows - 1)
        col = c.col.coerceIn(0, cols - 1)
        style = c.style
        pendingWrap = false
    }

    private fun reset() {
        setAlternateScreen(false)
        lines = ArrayList<Line>().apply { repeat(rows) { add(Line()) } }
        screenTop = 0
        row = 0; col = 0
        style = CellStyle()
        scrollTop = 0; scrollBottom = rows - 1
        autoWrap = true; cursorVisible = true; applicationCursorKeys = false
        pendingWrap = false
        saved = null
    }

    /**
     * Match the pty's window size. The screen is the tail of the buffer, so the
     * cursor stays on the line it was on rather than at the same screen row.
     *
     * The blank rows below the prompt are padding that keeps the screen a full
     * [rows] tall, not content. They have to go before the new screen is
     * measured off the end of the buffer: counting them made a shrinking view
     * (the soft keyboard opening) push the screen down past the prompt, and the
     * cursor with it, which showed up as a run of blank lines appearing out of
     * nowhere when the keyboard came up.
     */
    fun resize(newRows: Int, newCols: Int) {
        val r = newRows.coerceIn(1, 500)
        val c = newCols.coerceIn(1, 1000)
        if (r == rows && c == cols) return
        val cursorLine = screenTop + row
        if (altSaved == null) trimBlankTail(cursorLine)
        rows = r
        cols = c
        scrollTop = 0
        scrollBottom = rows - 1
        screenTop = maxOf(0, lines.size - rows)
        row = (cursorLine - screenTop).coerceIn(0, rows - 1)
        col = col.coerceIn(0, cols - 1)
        pendingWrap = false
        ensureScreen()
        trim()
    }

    /** Drop the padding rows after the last one with content (or the cursor). */
    private fun trimBlankTail(cursorLine: Int) {
        var used = 0
        for (i in lines.indices) if (!lines[i].blank) used = i + 1
        used = maxOf(used, cursorLine + 1)
        while (lines.size > used) lines.removeAt(lines.size - 1)
    }

    /** Scrollback is bounded, but never below one screenful. */
    private fun trim() {
        val cap = maxOf(maxLines, rows)
        while (lines.size > cap && screenTop > 0) {
            lines.removeAt(0)
            screenTop--
        }
    }

    // ---------- output ----------

    /** Absolute index into [render]'s list of the line the cursor sits on. */
    val cursorRow: Int get() = screenTop + row
    val cursorCol: Int get() = col

    /**
     * How much of the buffer is worth drawing: everything up to the last line
     * with content, or the cursor if it sits below that. Without this the view
     * would always be padded out to a full screen of blank lines under the
     * prompt.
     */
    private fun visibleCount(): Int {
        var last = -1
        for (i in lines.indices) if (!lines[i].blank) last = i
        return (maxOf(last, screenTop + row) + 1).coerceIn(0, lines.size)
    }

    /** Snapshot the buffer as styled spans, one list per line. */
    fun render(): List<List<Span>> = (0 until visibleCount()).map { i ->
        val line = lines[i]
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
    fun plainText(): String = (0 until visibleCount())
        .joinToString("\n") { String(lines[it].cells.toCharArray()) }

    private fun applySgr(params: String) {
        val codes = if (params.isEmpty()) listOf(0)
        else params.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < codes.size) {
            when (val n = codes[i]) {
                0 -> style = CellStyle()
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(reverse = true)
                22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
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
                        2 -> {
                            val r = codes.getOrNull(i + 2) ?: 0
                            val g = codes.getOrNull(i + 3) ?: 0
                            val b = codes.getOrNull(i + 4) ?: 0
                            val rgb = TRUECOLOR or (r.coerceIn(0, 255) shl 16) or
                                (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                            style = if (isFg) style.copy(fg = rgb) else style.copy(bg = rgb)
                            i += 4
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
            i++
        }
    }

    companion object {
        /** Set on [CellStyle.fg]/[CellStyle.bg] to mark the low 24 bits as RGB. */
        const val TRUECOLOR = 1 shl 24

        /**
         * Our private OSC code: `ESC ] 1337 ; open=<url> BEL` asks the app to
         * open a URL on the phone. Written by the sandbox's `xdg-open` shim —
         * see SandboxInstaller.ensureBrowserBridge.
         */
        const val OSC_OPEN_URL = "1337"

        private const val ESC = '\u001B'
        private const val BEL = '\u0007'
    }
}
