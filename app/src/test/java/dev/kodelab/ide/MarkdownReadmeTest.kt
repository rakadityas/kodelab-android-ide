package dev.kodelab.ide

import dev.kodelab.ide.editor.Markdown
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Parse the project's own README and assert nothing renders as a break in the
 * middle of a sentence. Hand-written cases kept missing this: the real file has
 * wrapped list items, and their continuation lines were coming out as separate
 * paragraphs.
 */
class MarkdownReadmeTest {

    private fun readme(): File {
        // the test runs from app/, the README sits at the repo root
        val candidates = listOf(File("../README.md"), File("README.md"))
        return candidates.first { it.isFile }
    }

    private fun spansOf(b: Markdown.Block): List<Markdown.Span>? = when (b) {
        is Markdown.Block.Paragraph -> b.spans
        is Markdown.Block.Item -> b.spans
        is Markdown.Block.Quote -> b.spans
        else -> null
    }

    @Test
    fun `no block in the real README starts mid-sentence`() {
        val file = readme()
        assumeTrue(file.isFile)
        val blocks = Markdown.parse(file.readText())

        val suspects = blocks.mapNotNull(::spansOf)
            .map { spans -> spans.joinToString("") { it.text }.trim() }
            .filter { it.isNotEmpty() }
            // a block opening with a lowercase word is a sentence that was cut
            .filter { it.first().isLowerCase() && !it.startsWith("`") }

        assertTrue(
            "blocks starting mid-sentence: " + suspects.take(5),
            suspects.isEmpty(),
        )
    }

    @Test
    fun `a wrapped list item stays one item`() {
        val blocks = Markdown.parse(
            "- Enable developer options (tap Build number 7 times), then turn\n" +
                "  on USB debugging in the settings app.\n" +
                "- Second item\n",
        )
        val items = blocks.filterIsInstance<Markdown.Block.Item>()
        assertTrue("expected 2 items, got " + blocks.size + " blocks", items.size == 2)
        val first = items[0].spans.joinToString("") { it.text }
        assertTrue(first, first.endsWith("in the settings app."))
        assertTrue(blocks.none { it is Markdown.Block.Paragraph })
    }

    @Test
    fun `a fenced block inside a list item is still its own block`() {
        val blocks = Markdown.parse("1. Run this:\n   ```sh\n   echo hi\n   ```\n")
        assertTrue(blocks.any { it is Markdown.Block.Item })
        assertTrue(blocks.any { it is Markdown.Block.Code })
    }
}
