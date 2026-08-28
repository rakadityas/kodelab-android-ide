package dev.kodelab.ide

import dev.kodelab.ide.theme.VsThemeImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VsThemeImportTest {

    @Test
    fun `parses hex formats`() {
        assertEquals(0xFFFF0000.toInt(), VsThemeImport.parseArgb("#f00"))       // #RGB
        assertEquals(0xFFFF0000.toInt(), VsThemeImport.parseArgb("#FF0000"))    // #RRGGBB
        assertEquals(0x80FF0000.toInt(), VsThemeImport.parseArgb("#FF000080"))  // #RRGGBBAA
        assertEquals(0x88FF0000.toInt(), VsThemeImport.parseArgb("#f008"))      // #RGBA (8 -> 0x88)
        assertEquals(0xFF1E1E1E.toInt(), VsThemeImport.parseArgb("1e1e1e"))     // no hash
    }

    @Test
    fun `rejects bad colours`() {
        assertNull(VsThemeImport.parseArgb(null))
        assertNull(VsThemeImport.parseArgb(""))
        assertNull(VsThemeImport.parseArgb("#xyz"))
        assertNull(VsThemeImport.parseArgb("#12345"))   // 5 digits: unsupported
        assertNull(VsThemeImport.parseArgb("rgb(1,2,3)"))
    }

    @Test
    fun `luminance separates black and white`() {
        assertTrue(VsThemeImport.relativeLuminance(0xFF000000.toInt()) < 0.1)
        assertTrue(VsThemeImport.relativeLuminance(0xFFFFFFFF.toInt()) > 0.9)
    }

    @Test
    fun `dark decided by type then luminance`() {
        assertTrue(VsThemeImport.isDarkFrom("dark", 0xFFFFFFFF.toInt()))    // type wins
        assertFalse(VsThemeImport.isDarkFrom("light", 0xFF000000.toInt())) // type wins
        assertTrue(VsThemeImport.isDarkFrom(null, 0xFF101010.toInt()))     // dark bg
        assertFalse(VsThemeImport.isDarkFrom(null, 0xFFF0F0F0.toInt()))    // light bg
    }

    @Test
    fun `blend midpoint is halfway`() {
        val mid = VsThemeImport.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5)
        val channel = mid ushr 16 and 0xFF
        assertTrue(channel in 126..129)
    }

    @Test
    fun `slug is filesystem safe`() {
        assertEquals("import-one-dark-pro", VsThemeImport.slug("One Dark Pro!"))
        assertEquals("import-theme", VsThemeImport.slug("  "))
    }

    @Test
    fun `parses a minimal dark theme`() {
        val json = """
            {
              "name": "Test Dark",
              "type": "dark",
              "colors": {
                "editor.background": "#1e1e1e",
                "editor.foreground": "#d4d4d4",
                "focusBorder": "#007acc"
              }
            }
        """.trimIndent()
        val p = VsThemeImport.parse(json)
        assertEquals("Test Dark", p.name)
        assertEquals("import-test-dark", p.id)
        assertTrue(p.palette.isDark)
    }

    @Test
    fun `infers light from background when type missing`() {
        val json = """{ "name": "Paper", "colors": { "editor.background": "#fafafa", "editor.foreground": "#222222" } }"""
        val p = VsThemeImport.parse(json)
        assertFalse(p.palette.isDark)
    }

    @Test
    fun `rejects json without colors`() {
        assertThrows(IllegalArgumentException::class.java) {
            VsThemeImport.parse("""{ "name": "Empty" }""")
        }
    }

    @Test
    fun `rejects non-json`() {
        assertThrows(IllegalArgumentException::class.java) {
            VsThemeImport.parse("not json at all")
        }
    }
}
