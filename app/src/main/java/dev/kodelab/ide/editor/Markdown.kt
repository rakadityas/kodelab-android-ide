package dev.kodelab.ide.editor

/**
 * A small, original Markdown reader used by the "Read" view (REQ 2: read the
 * code — and the docs — comfortably).
 *
 * Deliberately a CommonMark *subset*, parsed into a block/span tree that the
 * Compose layer draws natively. No HTML is produced and no third-party parser
 * is bundled, so there's nothing to sanitise and nothing to license.
 *
 * Supported: ATX + setext headings, paragraphs, fenced and indented code,
 * bullet/ordered lists (nested), block quotes, thematic breaks, pipe tables,
 * and inline code / bold / italic / strikethrough / links / images.
 * Raw HTML is passed through as literal text.
 */
object Markdown {

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        /** Non-null when this span is a link target (absolute URL or repo-relative path). */
        val link: String? = null,
    )

    sealed interface Block {
        data class Heading(val level: Int, val spans: List<Span>) : Block
        data class Paragraph(val spans: List<Span>) : Block
        data class Code(val language: String?, val code: String) : Block
        /** One list row; [indent] is the nesting depth, [marker] the rendered bullet/number. */
        data class Item(val indent: Int, val marker: String, val spans: List<Span>) : Block
        data class Quote(val spans: List<Span>) : Block
        data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>) : Block
        data object Rule : Block
    }

    private val ATX = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([\\w+#.-]*)\\s*$")
    private val RULE = Regex("^\\s{0,3}([-*_])\\s*(\\1\\s*){2,}$")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)+\\|?\\s*$")

    fun parse(source: String): List<Block> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<Block>()
        val para = mutableListOf<String>()

        fun flushParagraph() {
            if (para.isEmpty()) return
            blocks += Block.Paragraph(inline(softWrap(para)))
            para.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // fenced code — the fence's own char/length must be matched to close
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flushParagraph()
                val close = fence.groupValues[1]
                val lang = fence.groupValues[2].takeIf { it.isNotBlank() }
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(close)) {
                    body += lines[i]; i++
                }
                i++ // consume the closing fence (or fall off the end)
                blocks += Block.Code(lang, body.joinToString("\n"))
                continue
            }

            if (line.isBlank()) { flushParagraph(); i++; continue }

            // setext heading: an underlined paragraph line (checked before the
            // thematic-break rule, which "---" would otherwise claim)
            val underline = line.trimEnd()
            if (para.isNotEmpty() && underline.isNotEmpty() &&
                (underline.all { it == '=' } || underline.all { it == '-' })
            ) {
                val level = if (underline[0] == '=') 1 else 2
                blocks += Block.Heading(level, inline(para.joinToString(" ").trim()))
                para.clear()
                i++
                continue
            }

            if (RULE.matches(line)) { flushParagraph(); blocks += Block.Rule; i++; continue }

            val atx = ATX.matchEntire(line)
            if (atx != null) {
                flushParagraph()
                blocks += Block.Heading(atx.groupValues[1].length, inline(atx.groupValues[2]))
                i++
                continue
            }

            val quote = QUOTE.matchEntire(line)
            if (quote != null) {
                flushParagraph()
                val body = mutableListOf(quote.groupValues[1])
                i++
                while (i < lines.size) {
                    val q = QUOTE.matchEntire(lines[i]) ?: break
                    body += q.groupValues[1]
                    i++
                }
                blocks += Block.Quote(inline(softWrap(body).trim()))
                continue
            }

            val bullet = BULLET.matchEntire(line)
            val ordered = if (bullet == null) ORDERED.matchEntire(line) else null
            if (bullet != null || ordered != null) {
                flushParagraph()
                val indent = (bullet ?: ordered!!).groupValues[1].length / 2
                val marker = if (bullet != null) "\u2022" else ordered!!.groupValues[2] + "."
                val body = mutableListOf((bullet ?: ordered!!).groupValues[3])
                i++
                // Lazy continuation: a wrapped list item indents its following
                // lines. They belong to the item — emitting them as their own
                // paragraph is what put breaks in the middle of a sentence.
                while (i < lines.size && continuesItem(lines[i])) {
                    body += lines[i].trim()
                    i++
                }
                blocks += Block.Item(indent, marker, inline(softWrap(body)))
                continue
            }

            // indented code block (4 spaces / a tab), only outside a paragraph
            if (para.isEmpty() && (line.startsWith("    ") || line.startsWith("\t"))) {
                val body = mutableListOf<String>()
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                    body += lines[i].removePrefix("\t").removePrefix("    "); i++
                }
                blocks += Block.Code(null, body.joinToString("\n").trimEnd())
                continue
            }

            // pipe table: a header row followed by a |---|---| separator
            if (line.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1])) {
                flushParagraph()
                val header = tableCells(line)
                val rows = mutableListOf<List<List<Span>>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += tableCells(lines[i]); i++
                }
                blocks += Block.Table(header, rows)
                continue
            }

            para += line
            i++
        }
        flushParagraph()
        return blocks
    }

    /** Is [line] the indented continuation of the list item above it? */
    private fun continuesItem(line: String): Boolean {
        if (line.isBlank()) return false
        if (!line.startsWith(" ") && !line.startsWith("\t")) return false
        // a nested item, or any block of its own, ends the continuation
        return !BULLET.matches(line) && !ORDERED.matches(line) && !ATX.matches(line) &&
            !RULE.matches(line) && !QUOTE.matches(line) && FENCE.matchEntire(line) == null
    }

    /**
     * Join a paragraph's source lines the way Markdown means them: a newline in
     * the source is a *soft* break and becomes a space, so a README hard-wrapped
     * at 80 columns reflows to whatever width it's read at. Only an explicit
     * hard break — two trailing spaces, or a trailing backslash — stays a break.
     */
    private fun softWrap(lines: List<String>): String {
        val sb = StringBuilder()
        lines.forEachIndexed { i, raw ->
            val hard = raw.endsWith("  ") || raw.endsWith("\\")
            sb.append(raw.trimEnd().removeSuffix("\\"))
            if (i < lines.size - 1) sb.append(if (hard) "\n" else " ")
        }
        return sb.toString()
    }

    private fun tableCells(line: String): List<List<Span>> =
        line.trim().trim('|').split('|').map { inline(it.trim()) }

    // ---------- inline ----------

    /** Parse inline markup into styled spans. Unmatched markers stay literal. */
    fun inline(text: String): List<Span> {
        val out = mutableListOf<Span>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        var strike = false
        var i = 0

        fun flush() {
            if (buf.isEmpty()) return
            out += Span(buf.toString(), bold = bold, italic = italic, strike = strike)
            buf.clear()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                // escaped punctuation
                c == '\\' && i + 1 < text.length && !text[i + 1].isLetterOrDigit() -> {
                    buf.append(text[i + 1]); i += 2
                }
                // inline code — a run of N backticks closes on the next run of N
                c == '`' -> {
                    val ticks = text.countRunAt(i, '`')
                    val open = i + ticks
                    val close = text.indexOfRun(open, '`', ticks)
                    if (close < 0) { buf.append(text, i, open); i = open } else {
                        flush()
                        out += Span(text.substring(open, close).trim(), code = true)
                        i = close + ticks
                    }
                }
                // image / link
                c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
                    val l = link(text, i + 1)
                    if (l == null) { buf.append(c); i++ } else {
                        flush()
                        out += Span("🖼 " + l.label.ifBlank { l.href }, italic = true, link = l.href)
                        i = l.end
                    }
                }
                c == '[' -> {
                    val l = link(text, i)
                    if (l == null) { buf.append(c); i++ } else {
                        flush()
                        out += Span(l.label, bold = bold, italic = italic, link = l.href)
                        i = l.end
                    }
                }
                // autolink <https://…>
                c == '<' -> {
                    val end = text.indexOf('>', i + 1)
                    val inner = if (end > 0) text.substring(i + 1, end) else ""
                    if (end > 0 && Regex("^[a-zA-Z][\\w+.-]*://\\S+$|^mailto:\\S+$").matches(inner)) {
                        flush()
                        out += Span(inner, link = inner)
                        i = end + 1
                    } else { buf.append(c); i++ }
                }
                c == '~' && text.startsWith("~~", i) -> {
                    if (!canToggle(text, i, "~~", strike)) { buf.append("~~"); i += 2 } else {
                        flush(); strike = !strike; i += 2
                    }
                }
                (c == '*' || c == '_') && text.startsWith("$c$c", i) -> {
                    if (!canToggle(text, i, "$c$c", bold)) { buf.append(c).append(c); i += 2 } else {
                        flush(); bold = !bold; i += 2
                    }
                }
                c == '*' || (c == '_' && text.isWordBoundary(i)) -> {
                    if (!canToggle(text, i, c.toString(), italic)) { buf.append(c); i++ } else {
                        flush(); italic = !italic; i++
                    }
                }
                // GFM-style bare autolink: READMEs often just paste the url.
                (c == 'h' || c == 'w') && BARE_URL.matchesAt(text, i) -> {
                    val m = BARE_URL.matchAt(text, i)!!
                    // trailing punctuation belongs to the sentence, not the url
                    val raw = m.value.trimEnd('.', ',', ';', ':', '!', '?')
                        .let { if (it.endsWith(')') && it.count { c2 -> c2 == '(' } < it.count { c2 -> c2 == ')' }) it.dropLast(1) else it }
                    flush()
                    out += Span(raw, link = if (raw.startsWith("www.")) "https://$raw" else raw)
                    i += raw.length
                }
                else -> { buf.append(c); i++ }
            }
        }
        flush()
        return if (out.isEmpty()) listOf(Span("")) else out
    }

    private val BARE_URL = Regex("(https?://|www\\.)[\\w@:%+.~#?&/=-]+")

    private data class Link(val label: String, val href: String, val end: Int)

    /** Parse `[label](href)` starting at the `[`; null when it isn't one. */
    private fun link(text: String, start: Int): Link? {
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        if (i >= text.length || depth != 0) return null
        val label = text.substring(start + 1, i)
        if (i + 1 >= text.length || text[i + 1] != '(') return null
        val close = text.indexOf(')', i + 2)
        if (close < 0) return null
        // drop an optional title: [x](url "title")
        val href = text.substring(i + 2, close).trim().substringBefore(' ').trim('<', '>')
        if (href.isEmpty()) return null
        return Link(label, href, close + 1)
    }

    private fun String.countRunAt(index: Int, ch: Char): Int {
        var n = 0
        while (index + n < length && this[index + n] == ch) n++
        return n
    }

    /** Index of a run of exactly [len] [ch] at or after [from], or -1. */
    private fun String.indexOfRun(from: Int, ch: Char, len: Int): Int {
        var i = from
        while (i < length) {
            if (this[i] == ch) {
                val n = countRunAt(i, ch)
                if (n == len) return i
                i += n
            } else i++
        }
        return -1
    }

    /**
     * Whether the [marker] at [i] really toggles emphasis. A marker only opens
     * when it hugs the text to its right *and* has a partner later in the line;
     * it only closes when it hugs the text to its left. That keeps arithmetic
     * ("2 * 3 * 4") and stray markers literal.
     */
    private fun canToggle(text: String, i: Int, marker: String, closing: Boolean): Boolean {
        if (closing) return text.getOrNull(i - marker.length)?.isWhitespace() == false
        val next = text.getOrNull(i + marker.length)
        return next != null && !next.isWhitespace() && text.indexOf(marker, i + marker.length) >= 0
    }

    /** `_` only opens/closes emphasis at a word boundary (so `snake_case` survives). */
    private fun String.isWordBoundary(i: Int): Boolean {
        val before = getOrNull(i - 1)
        val after = getOrNull(i + 1)
        return (before == null || !before.isLetterOrDigit()) || (after == null || !after.isLetterOrDigit())
    }

    /** True when [name] looks like a document we can render in the reader. */
    fun isMarkdownFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdown", "mkd")

    /**
     * True for the file a project's front page lives in — README.md, readme.txt,
     * README — which is what the rail's reader button is offered for.
     */
    fun isReadme(name: String): Boolean =
        name.substringBeforeLast('.', name).equals("readme", ignoreCase = true)
}
