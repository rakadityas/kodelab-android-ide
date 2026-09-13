package dev.kodelab.ide

import dev.kodelab.ide.terminal.SandboxInstaller
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest shell profile is a string the app writes into the rootfs, so the
 * things that can silently break it — an unexpanded Kotlin escape, a lost alias
 * — are worth pinning down off-device.
 */
class ShellProfileTest {

    private val profile = SandboxInstaller.shellProfileForTest()

    @Test
    fun `the prompt carries real escape characters, not the text backslash-u`() {
        assertTrue(profile.contains("\u001B["))
        assertTrue(!profile.contains("\\u001B"))
    }

    @Test
    fun `the prompt keeps the shell's own expansions literal`() {
        // \u and \w are for the shell to expand, not Kotlin
        assertTrue(profile.contains("\\u@kodelab"))
        assertTrue(profile.contains("\\w"))
    }

    @Test
    fun `colour aliases a desktop distro ships with are present`() {
        listOf("ls --color=auto", "grep --color=auto", "LS_COLORS", "COLORTERM=truecolor")
            .forEach { assertTrue(it, profile.contains(it)) }
    }

    /**
     * The xdg-open stand-in is what makes `gh auth login` open a browser on the
     * phone instead of printing a URL to copy by hand, and it is a shell script
     * built from a Kotlin string — exactly the place an escape goes missing.
     */
    @Test
    fun `the browser shim prints the escape sequence for printf to expand`() {
        val shim = SandboxInstaller.browserShimForTest()
        // printf expands the octal escapes itself, so they must reach it as
        // text rather than as characters Kotlin already turned into control bytes.
        assertTrue(shim.contains(BACKSLASH + "033]1337;open=%s" + BACKSLASH + "007"))
        assertTrue(!shim.contains("\u001B"))
        assertTrue(shim.startsWith("#!/bin/sh"))
        // the URL is quoted, so a shell metacharacter in it can't run
        assertTrue(shim.contains("\"${'$'}1\""))
    }

    private companion object {
        const val BACKSLASH = "\\"
    }
}
