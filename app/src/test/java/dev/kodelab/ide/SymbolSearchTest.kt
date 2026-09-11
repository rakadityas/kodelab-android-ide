package dev.kodelab.ide

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declaration heuristic behind "Go to definition (workspace)". It is a
 * heuristic, not a parser, so these cases pin down what it does and does not
 * claim to recognise.
 */
class SymbolSearchTest {

    // mirrors IdeViewModel.looksLikeDeclaration
    private fun looksLikeDeclaration(line: String, symbol: String): Boolean {
        val keywords = listOf(
            "fun", "class", "object", "interface", "val", "var", "def", "function",
            "const", "let", "struct", "enum", "type", "trait", "impl", "package",
            "public", "private", "protected", "static", "data class",
        )
        val before = line.substringBefore(symbol).trim()
        if (before.isEmpty()) return false
        val lastWord = before.split(' ', '\t', '(', '<').lastOrNull { it.isNotBlank() } ?: return false
        return keywords.any { it.equals(lastWord, ignoreCase = false) }
    }

    @Test
    fun `declarations across a few languages are recognised`() {
        assertTrue(looksLikeDeclaration("fun openFolder(uri: Uri) {", "openFolder"))
        assertTrue(looksLikeDeclaration("class TerminalScheme(", "TerminalScheme"))
        assertTrue(looksLikeDeclaration("    private val scheme = x", "scheme"))
        assertTrue(looksLikeDeclaration("def handler(self):", "handler"))
        assertTrue(looksLikeDeclaration("function boot() {", "boot"))
        assertTrue(looksLikeDeclaration("type Config struct {", "Config"))
    }

    @Test
    fun `plain uses are not mistaken for declarations`() {
        assertTrue(!looksLikeDeclaration("    openFolder(uri)", "openFolder"))
        assertTrue(!looksLikeDeclaration("return scheme.background", "scheme"))
        assertTrue(!looksLikeDeclaration("openFolder", "openFolder"))
    }

    @Test
    fun `a keyword-like word that is not the keyword does not count`() {
        assertTrue(!looksLikeDeclaration("myfun handler()", "handler"))
        assertTrue(!looksLikeDeclaration("FUN handler()", "handler"))
    }
}
