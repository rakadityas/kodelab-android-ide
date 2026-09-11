package dev.kodelab.ide

import dev.kodelab.ide.git.GitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitStatusTest {

    @Test
    fun `branch with upstream and ahead behind`() {
        val s = GitStatus.parse("## main...origin/main [ahead 1, behind 2]\n")
        assertEquals("main", s.branch)
        assertEquals("origin/main", s.upstream)
        assertEquals(1, s.ahead)
        assertEquals(2, s.behind)
        assertFalse(s.detached)
    }

    @Test
    fun `branch with upstream no divergence`() {
        val s = GitStatus.parse("## feature/x...origin/feature/x\n")
        assertEquals("feature/x", s.branch)
        assertEquals("origin/feature/x", s.upstream)
        assertEquals(0, s.ahead)
        assertEquals(0, s.behind)
    }

    @Test
    fun `local branch without upstream`() {
        val s = GitStatus.parse("## work-in-progress\n")
        assertEquals("work-in-progress", s.branch)
        assertNull(s.upstream)
    }

    @Test
    fun `no commits yet`() {
        val s = GitStatus.parse("## No commits yet on main\n")
        assertEquals("main", s.branch)
        assertNull(s.upstream)
    }

    @Test
    fun `detached head`() {
        val s = GitStatus.parse("## HEAD (no branch)\n")
        assertTrue(s.detached)
        assertNull(s.branch)
    }

    @Test
    fun `categorises staged, unstaged and untracked`() {
        val out = buildString {
            append("## main\n")
            append("M  staged.kt\n")     // staged modification
            append(" M dirty.kt\n")      // unstaged modification
            append("MM both.kt\n")       // staged + more unstaged edits
            append("?? new.txt\n")       // untracked
            append("A  added.kt\n")      // newly added (staged)
            append(" D gone.kt\n")       // deleted in worktree
        }
        val s = GitStatus.parse(out)
        assertEquals(6, s.files.size)

        val staged = s.staged.map { it.path }.toSet()
        assertEquals(setOf("staged.kt", "both.kt", "added.kt"), staged)

        val unstaged = s.unstaged.map { it.path }.toSet()
        assertEquals(setOf("dirty.kt", "both.kt", "new.txt", "gone.kt"), unstaged)

        val untracked = s.files.filter { it.untracked }.map { it.path }
        assertEquals(listOf("new.txt"), untracked)
    }

    @Test
    fun `rename keeps new and original path`() {
        val s = GitStatus.parse("## main\nR  old/name.kt -> new/name.kt\n")
        val f = s.files.single()
        assertEquals("new/name.kt", f.path)
        assertEquals("old/name.kt", f.origPath)
        assertTrue(f.staged)
    }

    @Test
    fun `quoted path with spaces is unquoted`() {
        val s = GitStatus.parse("## main\n?? \"a file.txt\"\n")
        assertEquals("a file.txt", s.files.single().path)
    }

    @Test
    fun `clean tree reports no files`() {
        val s = GitStatus.parse("## main...origin/main\n")
        assertTrue(s.clean)
    }

    @Test
    fun `conflicted entry is flagged`() {
        val s = GitStatus.parse("## main\nUU merge.kt\n")
        assertTrue(s.files.single().conflicted)
    }
}
