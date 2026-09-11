package dev.kodelab.ide

import dev.kodelab.ide.editor.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    private fun text(spans: List<Markdown.Span>) = spans.joinToString("") { it.text }

    @Test
    fun `atx headings carry their level`() {
        val blocks = Markdown.parse("# Title\n\n### Deeper ###")
        assertEquals(2, blocks.size)
        assertEquals(1, (blocks[0] as Markdown.Block.Heading).level)
        assertEquals("Title", text((blocks[0] as Markdown.Block.Heading).spans))
        assertEquals(3, (blocks[1] as Markdown.Block.Heading).level)
        assertEquals("Deeper", text((blocks[1] as Markdown.Block.Heading).spans))
    }

    @Test
    fun `setext underline wins over the thematic break`() {
        val blocks = Markdown.parse("Kodelab\n-------\n")
        val h = blocks.single() as Markdown.Block.Heading
        assertEquals(2, h.level)
        assertEquals("Kodelab", text(h.spans))
    }

    @Test
    fun `standalone dashes are a rule`() {
        val blocks = Markdown.parse("a\n\n---\n\nb")
        assertTrue(blocks[1] is Markdown.Block.Rule)
    }

    @Test
    fun `fenced code keeps its body verbatim and records the language`() {
        val blocks = Markdown.parse("```kotlin\nval x = *1*\n  indented\n```")
        val code = blocks.single() as Markdown.Block.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = *1*\n  indented", code.code)
    }

    @Test
    fun `unclosed fence still yields a code block`() {
        val code = Markdown.parse("```\nno end").single() as Markdown.Block.Code
        assertEquals("no end", code.code)
        assertNull(code.language)
    }

    @Test
    fun `lists keep nesting depth and numbering`() {
        val blocks = Markdown.parse("- one\n  - two\n\n1. first\n2. second")
        val items = blocks.filterIsInstance<Markdown.Block.Item>()
        assertEquals(4, items.size)
        assertEquals(0, items[0].indent)
        assertEquals(1, items[1].indent)
        assertEquals("•", items[0].marker)
        assertEquals("1.", items[2].marker)
        assertEquals("second", text(items[3].spans))
    }

    @Test
    fun `block quote soft-wraps its lines like any paragraph`() {
        val q = Markdown.parse("> one\n> two").single() as Markdown.Block.Quote
        assertEquals("one two", text(q.spans))
    }

    @Test
    fun `a quote keeps an explicit hard break`() {
        val q = Markdown.parse("> one  \n> two").single() as Markdown.Block.Quote
        assertEquals("one\ntwo", text(q.spans))
    }

    @Test
    fun `pipe table splits header and rows`() {
        val t = Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |").single() as Markdown.Block.Table
        assertEquals(listOf("a", "b"), t.header.map { text(it) })
        assertEquals(listOf(listOf("1", "2")), t.rows.map { r -> r.map { text(it) } })
    }

    @Test
    fun `inline emphasis and code are styled`() {
        val spans = Markdown.inline("**bold** and *it* and `x = 1` and ~~gone~~")
        assertEquals("bold", spans.first { it.bold }.text)
        assertEquals("it", spans.first { it.italic && !it.code }.text)
        assertEquals("x = 1", spans.first { it.code }.text)
        assertEquals("gone", spans.first { it.strike }.text)
    }

    @Test
    fun `underscores inside words are literal`() {
        val spans = Markdown.inline("call snake_case_name here")
        assertEquals("call snake_case_name here", text(spans))
        assertTrue(spans.none { it.italic })
    }

    @Test
    fun `unmatched markers stay literal`() {
        assertEquals("2 * 3 * ", text(Markdown.inline("2 * 3 * ")))
        assertEquals("a `b", text(Markdown.inline("a `b")))
    }

    @Test
    fun `links and images expose their target`() {
        val link = Markdown.inline("see [docs](https://example.org/x \"t\")").first { it.link != null }
        assertEquals("https://example.org/x", link.link)
        assertEquals("docs", link.text)

        val img = Markdown.inline("![logo](assets/logo.png)").first { it.link != null }
        assertEquals("assets/logo.png", img.link)
        assertTrue(img.text.contains("logo"))
    }

    @Test
    fun `autolinks are recognised, other angle brackets are not`() {
        assertEquals("https://a.dev", Markdown.inline("<https://a.dev>").single().link)
        assertEquals("<div>", text(Markdown.inline("<div>")))
    }

    @Test
    fun `escaped markers are literal`() {
        val spans = Markdown.inline("\\*not italic\\*")
        assertEquals("*not italic*", text(spans))
        assertTrue(spans.none { it.italic })
    }

    @Test
    fun `bare urls are linkified the way github does`() {
        val spans = Markdown.inline("see https://example.org/a/b for more")
        val link = spans.first { it.link != null }
        assertEquals("https://example.org/a/b", link.link)
        assertEquals("https://example.org/a/b", link.text)
    }

    @Test
    fun `a bare url keeps sentence punctuation out of the target`() {
        assertEquals("https://example.org/x", Markdown.inline("go to https://example.org/x.").first { it.link != null }.link)
        assertEquals("https://a.dev/p", Markdown.inline("(see https://a.dev/p)").first { it.link != null }.link)
    }

    @Test
    fun `www urls get a scheme`() {
        val link = Markdown.inline("www.example.org/docs").first { it.link != null }
        assertEquals("https://www.example.org/docs", link.link)
        assertEquals("www.example.org/docs", link.text)
    }

    @Test
    fun `an explicit markdown link still wins over the bare matcher`() {
        val spans = Markdown.inline("[docs](https://example.org/x)")
        assertEquals(1, spans.count { it.link != null })
        assertEquals("docs", spans.first { it.link != null }.text)
    }

    @Test
    fun `a hard-wrapped paragraph reflows into one line`() {
        val blocks = Markdown.parse("This sentence was wrapped\nat eighty columns in the\nsource file.")
        val p = blocks.single() as Markdown.Block.Paragraph
        assertEquals("This sentence was wrapped at eighty columns in the source file.", text(p.spans))
    }

    @Test
    fun `two trailing spaces keep an explicit break`() {
        val p = Markdown.parse("line one  \nline two").single() as Markdown.Block.Paragraph
        assertEquals("line one\nline two", text(p.spans))
    }

    @Test
    fun `markdown file names are detected`() {
        assertTrue(Markdown.isMarkdownFile("README.md"))
        assertTrue(Markdown.isMarkdownFile("notes.MARKDOWN"))
        assertTrue(!Markdown.isMarkdownFile("Main.kt"))
    }
}
