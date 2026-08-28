package dev.kodelab.ide

import dev.kodelab.ide.editor.Languages
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguagesTest {

    @Test
    fun `maps common extensions`() {
        assertEquals("kotlin", Languages.forFileName("Main.kt"))
        assertEquals("typescript", Languages.forFileName("app.tsx"))
        assertEquals("python", Languages.forFileName("train.py"))
        assertEquals("markdown", Languages.forFileName("README.md"))
        assertEquals("json", Languages.forFileName("package.json"))
        assertEquals("cpp", Languages.forFileName("engine.hpp"))
    }

    @Test
    fun `special file names win over extensions`() {
        assertEquals("dockerfile", Languages.forFileName("Dockerfile"))
        assertEquals("makefile", Languages.forFileName("Makefile"))
        assertEquals("cmake", Languages.forFileName("CMakeLists.txt"))
    }

    @Test
    fun `unknown falls back to plaintext`() {
        assertEquals("plaintext", Languages.forFileName("data.xyzq"))
        assertEquals("plaintext", Languages.forFileName("no_extension"))
    }
}
