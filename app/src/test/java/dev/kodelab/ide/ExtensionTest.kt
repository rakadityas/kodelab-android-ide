package dev.kodelab.ide

import dev.kodelab.ide.ext.ExtensionAudit
import dev.kodelab.ide.ext.ExtensionManifest
import dev.kodelab.ide.ext.SpdxLicenses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionTest {

    // ---- SPDX ----

    @Test fun `single permissive licenses pass`() {
        listOf("MIT", "Apache-2.0", "BSD-3-Clause", "ISC", "OFL-1.1", "0BSD").forEach {
            assertTrue(it, SpdxLicenses.isPermissive(it))
        }
    }

    @Test fun `copyleft and unknown fail`() {
        listOf("GPL-3.0-only", "AGPL-3.0-or-later", "LGPL-2.1-only", "MPL-2.0", "Weird-1.0", "")
            .forEach { assertFalse(it, SpdxLicenses.isPermissive(it)) }
    }

    @Test fun `OR passes when any branch is permissive`() {
        assertTrue(SpdxLicenses.isPermissive("MIT OR GPL-3.0-only"))
        assertTrue(SpdxLicenses.isPermissive("(Apache-2.0 OR LGPL-3.0-only)"))
        assertFalse(SpdxLicenses.isPermissive("GPL-3.0-only OR AGPL-3.0-only"))
    }

    @Test fun `AND requires all branches permissive`() {
        assertTrue(SpdxLicenses.isPermissive("MIT AND Apache-2.0"))
        assertFalse(SpdxLicenses.isPermissive("MIT AND GPL-3.0-only"))
    }

    @Test fun `precedence AND binds tighter than OR`() {
        // GPL AND MIT -> false; OR Apache-2.0 -> true
        assertTrue(SpdxLicenses.isPermissive("GPL-3.0-only AND MIT OR Apache-2.0"))
    }

    @Test fun `WITH exception keeps base permissiveness`() {
        assertTrue(SpdxLicenses.isPermissive("Apache-2.0 WITH LLVM-exception"))
    }

    @Test fun `leaves lists identifiers only`() {
        assertEquals(listOf("MIT", "GPL-3.0-only"), SpdxLicenses.leaves("MIT OR GPL-3.0-only"))
    }

    // ---- manifest parsing ----

    private val manifest = """
        {
          "id": "acme.kotlin-pack",
          "name": "Kotlin Pack",
          "version": "1.2.0",
          "publisher": "acme",
          "license": "MIT",
          "contributes": {
            "themes": [ { "label": "Acme Dark", "file": "acme-dark.json" } ],
            "snippets": [
              { "name": "main", "prefix": "main", "body": ["fun main() {", "    $0", "}"], "languages": ["kotlin"] }
            ],
            "grammars": [ { "languageId": "kotlin", "extensions": [".kt", ".kts"] } ],
            "languageServers": [ { "languageId": "kotlin", "install": ["apk add kotlin-lsp"], "command": ["kotlin-lsp"] } ]
          }
        }
    """.trimIndent()

    @Test fun `parses all contribution types`() {
        val m = ExtensionManifest.parse(manifest)
        assertEquals("acme.kotlin-pack", m.id)
        assertEquals("1.2.0", m.version)
        assertEquals(1, m.themes.size)
        assertEquals("acme-dark.json", m.themes[0].file)
        assertEquals(1, m.snippets.size)
        assertEquals("fun main() {\n    \$0\n}", m.snippets[0].body) // array body joined
        assertEquals(listOf("kotlin"), m.snippets[0].languages)
        assertEquals(listOf(".kt", ".kts"), m.grammars[0].extensions)
        assertEquals(listOf("kotlin-lsp"), m.lspRecipes[0].command)
        assertEquals(4, m.contributionCount)
    }

    @Test fun `snippet body may be a plain string`() {
        val m = ExtensionManifest.parse(
            """{ "id":"x", "license":"MIT", "contributes": { "snippets":[ {"prefix":"p","body":"just one line"} ] } }""",
        )
        assertEquals("just one line", m.snippets.single().body)
    }

    @Test fun `manifest without id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExtensionManifest.parse("""{ "name": "no id" }""")
        }
    }

    // ---- audit ----

    @Test fun `permissive extension is allowed`() {
        val r = ExtensionAudit.audit(ExtensionManifest.parse(manifest))
        assertTrue(r.allowed)
        assertTrue(r.issues.isEmpty())
    }

    @Test fun `missing license is flagged`() {
        val m = ExtensionManifest.parse("""{ "id":"x", "contributes": {} }""")
        val r = ExtensionAudit.audit(m)
        assertEquals(ExtensionAudit.Verdict.FLAGGED, r.verdict)
        assertTrue(r.issues.any { it.reason.contains("no license") })
    }

    @Test fun `copyleft extension is flagged with reason`() {
        val m = ExtensionManifest.parse("""{ "id":"x", "license":"GPL-3.0-only", "contributes": {} }""")
        val r = ExtensionAudit.audit(m)
        assertFalse(r.allowed)
        assertTrue(r.issues.single().reason.contains("copyleft"))
    }

    @Test fun `a single bad contributed file flags an otherwise-permissive extension`() {
        val m = ExtensionManifest.parse(
            """{ "id":"x", "license":"MIT", "contributes": {
                 "themes":[ {"label":"T","file":"t.json","license":"GPL-3.0-only"} ] } }""",
        )
        val r = ExtensionAudit.audit(m)
        assertFalse(r.allowed)
        assertTrue(r.issues.any { it.subject.contains("theme") })
    }

    @Test fun `contributed file inherits permissive extension license`() {
        val m = ExtensionManifest.parse(
            """{ "id":"x", "license":"MIT", "contributes": {
                 "grammars":[ {"languageId":"kotlin"} ] } }""",
        )
        assertTrue(ExtensionAudit.audit(m).allowed) // grammar has no own license -> inherits MIT
    }
}
