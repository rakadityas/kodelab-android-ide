package dev.kodelab.ide

import dev.kodelab.ide.terminal.SandboxInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SandboxInstallerTest {

    private val packagesIndex = """
        Package: libtalloc
        Version: 2.4.3
        Filename: pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb
        SHA256: aaaa1111

        Package: proot
        Installed-Size: 336
        Version: 5.1.107.92
        Depends: libandroid-shmem, libtalloc
        Filename: pool/main/p/proot/proot_5.1.107.92_aarch64.deb
        SHA256: bbbb2222
    """.trimIndent()

    @Test
    fun `parses a package stanza`() {
        val proot = SandboxInstaller.parseDebEntry(packagesIndex, "proot")!!
        assertEquals("5.1.107.92", proot.version)
        assertEquals("pool/main/p/proot/proot_5.1.107.92_aarch64.deb", proot.filename)
        assertEquals("bbbb2222", proot.sha256)
    }

    @Test
    fun `picks the right package among several`() {
        val talloc = SandboxInstaller.parseDebEntry(packagesIndex, "libtalloc")!!
        assertEquals("2.4.3", talloc.version)
        assertEquals("aaaa1111", talloc.sha256)
    }

    @Test
    fun `missing package returns null`() {
        assertNull(SandboxInstaller.parseDebEntry(packagesIndex, "nonesuch"))
    }

    @Test
    fun `parses alpine minirootfs entry`() {
        val yaml = """
            - arch: aarch64
              version: 3.20.10
              flavor: alpine-minirootfs
              file: alpine-minirootfs-3.20.10-aarch64.tar.gz
              sha256: 61ac877fdbcee6914731bc22a4ed5668ea3470f201f97a7078931c48b71bbeec
            - arch: aarch64
              flavor: alpine-standard
              file: alpine-standard-3.20.10-aarch64.iso
              sha256: deadbeef
        """.trimIndent()
        val (file, sha) = SandboxInstaller.parseAlpineYaml(yaml)!!
        assertEquals("alpine-minirootfs-3.20.10-aarch64.tar.gz", file)
        assertEquals("61ac877fdbcee6914731bc22a4ed5668ea3470f201f97a7078931c48b71bbeec", sha)
    }
}
