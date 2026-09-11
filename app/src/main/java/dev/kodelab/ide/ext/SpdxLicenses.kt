package dev.kodelab.ide.ext

/**
 * A small SPDX license-expression evaluator used to keep Kodelab IP-safe:
 * declarative extensions are only auto-activated when their declared license is
 * permissive (safe to reuse/redistribute without copyleft or unknown terms).
 *
 * This intentionally errs on the side of caution — anything not clearly
 * permissive is *flagged*, never silently loaded. It classifies leaves as
 * PERMISSIVE / COPYLEFT / UNKNOWN and evaluates AND/OR/WITH per the SPDX grammar
 * (AND binds tighter than OR).
 */
object SpdxLicenses {

    enum class Leaf { PERMISSIVE, COPYLEFT, UNKNOWN }

    /** SPDX ids safe to reuse without copyleft obligations. */
    val PERMISSIVE: Set<String> = setOf(
        "MIT", "MIT-0", "X11", "ISC", "0BSD",
        "BSD-1-Clause", "BSD-2-Clause", "BSD-3-Clause", "BSD-3-Clause-Clear",
        "Apache-2.0", "Apache-1.1",
        "Zlib", "libpng-2.0", "bzip2-1.0.6",
        "Unlicense", "CC0-1.0", "WTFPL",
        "OFL-1.1", "Ubuntu-font-1.0",
        "Python-2.0", "PostgreSQL", "NCSA", "Boost-1.0", "BSL-1.0",
    )

    /** Copyleft ids: usable but with obligations — flagged, not auto-activated. */
    val COPYLEFT: Set<String> = setOf(
        "GPL-2.0-only", "GPL-2.0-or-later", "GPL-3.0-only", "GPL-3.0-or-later",
        "LGPL-2.1-only", "LGPL-2.1-or-later", "LGPL-3.0-only", "LGPL-3.0-or-later",
        "AGPL-3.0-only", "AGPL-3.0-or-later",
        "MPL-2.0", "EPL-1.0", "EPL-2.0", "CDDL-1.0", "CDDL-1.1",
        "GPL-2.0", "GPL-3.0", "LGPL-2.1", "LGPL-3.0", "AGPL-3.0", // deprecated ids
    )

    fun classifyLeaf(id: String): Leaf = when (id.trim()) {
        in PERMISSIVE -> Leaf.PERMISSIVE
        in COPYLEFT -> Leaf.COPYLEFT
        else -> Leaf.UNKNOWN
    }

    /**
     * True when the expression can be satisfied entirely by permissive licenses
     * (e.g. "MIT OR GPL-3.0-only" is permissive — you may take the MIT branch).
     */
    fun isPermissive(expression: String?): Boolean {
        val expr = expression?.trim().orEmpty()
        if (expr.isEmpty()) return false
        return runCatching { Parser(tokenize(expr)).parseExpr() }.getOrDefault(false)
    }

    /** All distinct leaf identifiers referenced by the expression. */
    fun leaves(expression: String?): List<String> {
        val expr = expression?.trim().orEmpty()
        if (expr.isEmpty()) return emptyList()
        return tokenize(expr).filter { it != "(" && it != ")" && !it.equals("AND", true) &&
            !it.equals("OR", true) && !it.equals("WITH", true) }
            .distinct()
    }

    // --- tiny recursive-descent evaluator over the boolean "is permissive" ---

    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
        for (ch in s) {
            when (ch) {
                '(', ')' -> { flush(); out += ch.toString() }
                ' ', '\t', '\n' -> flush()
                else -> sb.append(ch)
            }
        }
        flush()
        return out
    }

    private class Parser(private val tokens: List<String>) {
        private var pos = 0
        private fun peek() = tokens.getOrNull(pos)
        private fun next() = tokens.getOrNull(pos)?.also { pos++ }

        // expr := term (OR term)*
        fun parseExpr(): Boolean {
            var value = parseTerm()
            while (peek()?.equals("OR", true) == true) {
                next()
                val rhs = parseTerm()
                value = value || rhs
            }
            return value
        }

        // term := factor (AND factor)*
        private fun parseTerm(): Boolean {
            var value = parseFactor()
            while (peek()?.equals("AND", true) == true) {
                next()
                val rhs = parseFactor()
                value = value && rhs
            }
            return value
        }

        // factor := '(' expr ')' | ID (WITH ID)?
        private fun parseFactor(): Boolean {
            val t = next() ?: return false
            if (t == "(") {
                val inner = parseExpr()
                if (peek() == ")") next()
                return inner
            }
            // an optional "WITH <exception>" doesn't change the base permissiveness
            if (peek()?.equals("WITH", true) == true) { next(); next() }
            return classifyLeaf(t.removeSuffix("+")) == Leaf.PERMISSIVE
        }
    }
}
