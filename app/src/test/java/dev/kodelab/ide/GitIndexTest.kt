package dev.kodelab.ide

import dev.kodelab.ide.git.GitIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GitIndexTest {

    /** Build a minimal but real v2 index, the way git lays one out. */
    private fun index(version: Int, vararg entries: Triple<String, Int, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("DIRC".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(version).array())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(entries.size).array())
        entries.forEach { (path, size, sha) ->
            val body = ByteArrayOutputStream()
            body.write(ByteArray(36))               // ctime..gid
            body.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(size).array())
            body.write(sha.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            body.write(ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(path.length.toShort()).array())
            body.write(path.toByteArray())
            val padded = ((body.size() + 8) / 8) * 8
            body.write(ByteArray(padded - body.size()))
            out.write(body.toByteArray())
        }
        return out.toByteArray()
    }

    private val shaA = "a".repeat(40)
    private val shaB = "b".repeat(40)

    @Test
    fun `entries carry their path, size and sha`() {
        val parsed = GitIndex.parse(
            index(2, Triple("src/Main.kt", 120, shaA), Triple("README.md", 4096, shaB)),
        )
        assertEquals(listOf("src/Main.kt", "README.md"), parsed.map { it.path })
        assertEquals(120L, parsed[0].size)
        assertEquals(shaB, parsed[1].sha)
    }

    @Test
    fun `a version 3 index parses too`() {
        val parsed = GitIndex.parse(index(3, Triple("a.txt", 1, shaA)))
        assertEquals("a.txt", parsed.single().path)
    }

    @Test
    fun `a non-index file is rejected rather than half-read`() {
        val e = runCatching { GitIndex.parse("not an index at all".toByteArray()) }.exceptionOrNull()
        assertTrue(e is GitIndex.UnsupportedIndex)
    }

    @Test
    fun `version 4 is rejected because its paths are compressed`() {
        val e = runCatching { GitIndex.parse(index(4, Triple("a", 1, shaA))) }.exceptionOrNull()
        assertTrue(e is GitIndex.UnsupportedIndex)
    }

    @Test
    fun `blob sha matches what git computes`() {
        // git hash-object for an empty blob, and for "hello\n"
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitIndex.blobSha(ByteArray(0)))
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitIndex.blobSha("hello\n".toByteArray()))
    }

    @Test
    fun `HEAD gives the branch, or null when detached`() {
        assertEquals("main", GitIndex.branchFromHead("ref: refs/heads/main\n"))
        assertEquals("feature/x", GitIndex.branchFromHead("ref: refs/heads/feature/x"))
        assertNull(GitIndex.branchFromHead("9fceb02d0ae598e95dc970b74767f19372d61af8"))
    }
}
