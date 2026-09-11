package dev.kodelab.ide

import dev.kodelab.ide.terminal.SandboxInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Mapping a host path to the path the guest sees it at. The terminal's working
 * directory and every git command depend on it.
 *
 * These call the real function. An earlier version of this test re-implemented
 * the logic instead, and passed while the shipped code compared against the
 * literal text "\${'$'}root/" -- so every folder fell through to the bind branch
 * and the shell came up in a host path. Mirrored logic tests the mirror.
 */
class GuestPathTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun rootfs(): File = tmp.newFolder("files", "sandbox", "rootfs")

    @Test
    fun `a repo cloned in the terminal needs no bind`() {
        val root = rootfs()
        val where = SandboxInstaller.guestLocationIn(root, File(root, "root/myrepo").path)
        assertEquals("/root/myrepo", where.path)
        assertNull(where.bind)
    }

    @Test
    fun `the rootfs itself is the guest root`() {
        val root = rootfs()
        assertEquals("/", SandboxInstaller.guestLocationIn(root, root.path).path)
    }

    @Test
    fun `a folder outside the rootfs is bound in at its own path`() {
        val root = rootfs()
        val outside = tmp.newFolder("elsewhere", "project")
        val where = SandboxInstaller.guestLocationIn(root, outside.path)
        assertEquals(outside.path, where.path)
        assertEquals(outside.path, where.bind)
    }

    @Test
    fun `the same directory reached through a symlink still maps inside`() {
        // the device case: app storage is reachable as both /data/user/0/<pkg>
        // and /data/data/<pkg>, and the path came back under the other name
        val root = rootfs()
        File(root, "root/myrepo").mkdirs()
        val link = File(tmp.root, "link")
        Files.createSymbolicLink(link.toPath(), tmp.root.toPath().resolve("files"))

        val viaLink = File(link, "sandbox/rootfs/root/myrepo").path
        val where = SandboxInstaller.guestLocationIn(root, viaLink)
        assertEquals("/root/myrepo", where.path)
        assertNull(where.bind)
    }

    @Test
    fun `a path that merely starts with the rootfs name is not inside it`() {
        val root = rootfs()
        val sibling = root.path + "-backup"
        val where = SandboxInstaller.guestLocationIn(root, sibling)
        assertEquals(sibling, where.path)
        assertEquals(sibling, where.bind)
    }
}
