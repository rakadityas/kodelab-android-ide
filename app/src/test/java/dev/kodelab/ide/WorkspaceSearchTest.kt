package dev.kodelab.ide

import dev.kodelab.ide.workspace.WorkspaceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSearchTest {

    private val text = """
        fun main() {
            val greeting = "hello world"
            println(greeting)   // hello again
        }
    """.trimIndent()

    @Test
    fun `finds every occurrence with line and span`() {
        val hits = WorkspaceRepository.findMatches(text, "hello", ignoreCase = true, limit = 100)
        assertEquals(2, hits.size)
        // first hit is on the greeting line
        assertEquals(2, hits[0].line)
        assertEquals("hello", hits[0].text.substring(hits[0].start, hits[0].end))
        // both hits carry a valid, in-bounds span
        hits.forEach { assertTrue(it.start in 0..it.end && it.end <= it.text.length) }
    }

    @Test
    fun `case-insensitive matches mixed case`() {
        val hits = WorkspaceRepository.findMatches("Hello HELLO hElLo", "hello", ignoreCase = true, limit = 100)
        assertEquals(3, hits.size)
    }

    @Test
    fun `case-sensitive respects case`() {
        val hits = WorkspaceRepository.findMatches("Hello HELLO hello", "hello", ignoreCase = false, limit = 100)
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].line)
    }

    @Test
    fun `limit stops collecting`() {
        val hits = WorkspaceRepository.findMatches("a a a a a", "a", ignoreCase = true, limit = 2)
        assertEquals(2, hits.size)
    }

    @Test
    fun `blank query yields nothing`() {
        assertTrue(WorkspaceRepository.findMatches(text, "", ignoreCase = true, limit = 100).isEmpty())
    }

    @Test
    fun `long lines are snippet-truncated but span stays in bounds`() {
        val long = "x".repeat(1000) + "needle"
        val hits = WorkspaceRepository.findMatches(long, "needle", ignoreCase = true, limit = 10)
        assertEquals(1, hits.size)
        val m = hits[0]
        assertTrue(m.text.length <= 240)
        assertTrue(m.end <= m.text.length)
        assertTrue(m.start <= m.end)
    }

    @Test
    fun `binary extensions are skipped, source is not`() {
        assertTrue(WorkspaceRepository.looksBinary("logo.PNG"))
        assertTrue(WorkspaceRepository.looksBinary("app.so"))
        assertTrue(WorkspaceRepository.looksBinary("bundle.jar"))
        assertFalse(WorkspaceRepository.looksBinary("Main.kt"))
        assertFalse(WorkspaceRepository.looksBinary("README"))
    }

    @Test
    fun `skip dirs cover the usual build and vcs folders`() {
        assertTrue("node_modules" in WorkspaceRepository.SKIP_DIRS)
        assertTrue(".git" in WorkspaceRepository.SKIP_DIRS)
        assertTrue("build" in WorkspaceRepository.SKIP_DIRS)
    }
}
