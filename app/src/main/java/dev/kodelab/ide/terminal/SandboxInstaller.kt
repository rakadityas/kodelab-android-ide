package dev.kodelab.ide.terminal

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs the Linux sandbox (REQ 5 "install anything"): a proot binary and an
 * Alpine minirootfs, downloaded AT RUNTIME from their projects' own servers —
 * never redistributed in the APK (see NOTICE / docs/IP-SAFETY.md).
 *
 *  - proot (GPL-2.0) + libtalloc/libandroid-shmem come from the Termux package
 *    mirror as unmodified prebuilt Android binaries; we only unpack the .debs.
 *  - Alpine minirootfs (~4 MB) comes from dl-cdn.alpinelinux.org, sha256-checked
 *    against the mirror's latest-releases.yaml.
 *
 * Everything lands in filesDir/sandbox; running the shell through proot is
 * ShellSession's job. On targetSdk 29+ the app can't execve() app-storage files
 * directly (W^X), so proot is launched through the system linker — see
 * [execPrefix].
 */
class SandboxInstaller(private val context: Context) {

    sealed interface Status {
        data object NotInstalled : Status
        data class Installing(val step: String) : Status
        data object Installed : Status
        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(
        if (marker.exists()) Status.Installed else Status.NotInstalled,
    )
    val status: StateFlow<Status> = _status

    val sandboxDir: File get() = File(context.filesDir, "sandbox")
    val rootfsDir: File get() = File(sandboxDir, "rootfs")
    val prootBin: File get() = File(sandboxDir, "bin/proot")
    val prootLoader: File get() = File(sandboxDir, "libexec/proot/loader")
    val libDir: File get() = File(sandboxDir, "lib")
    val tmpDir: File get() = File(sandboxDir, "tmp")
    private val marker: File get() = File(sandboxDir, ".installed")

    val isInstalled: Boolean get() = marker.exists()

    /**
     * Prefix to run an app-private ELF (proot) with. When the app targets API 29+
     * the platform forbids execve() of files in app storage (W^X), so we launch
     * proot through the system dynamic linker, which is permitted. Empty when the
     * app targets ≤28 (direct exec is allowed) — keeping that verified path intact.
     */
    val execPrefix: List<String>
        get() {
            val targetsQPlus = context.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.Q
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !targetsQPlus) return emptyList()
            val linker64 = File("/system/bin/linker64")
            val linker = File("/system/bin/linker")
            return when {
                linker64.exists() -> listOf(linker64.path)
                linker.exists() -> listOf(linker.path)
                else -> emptyList()
            }
        }

    suspend fun install() = withContext(Dispatchers.IO) {
        if (isInstalled) return@withContext
        runCatching {
            sandboxDir.mkdirs(); libDir.mkdirs(); tmpDir.mkdirs()
            File(sandboxDir, "bin").mkdirs()

            step("resolving proot package versions…")
            val index = fetchText("$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages")
            val debs = listOf("proot", "libtalloc", "libandroid-shmem").map { pkg ->
                parseDebEntry(index, pkg)
                    ?: error("package $pkg not found in the Termux index")
            }

            debs.forEach { deb ->
                step("downloading ${deb.name} ${deb.version}…")
                val file = File(sandboxDir, "${deb.name}.deb")
                download("$TERMUX_REPO/${deb.filename}", file)
                check(sha256(file) == deb.sha256) { "${deb.name}: checksum mismatch" }
                step("unpacking ${deb.name}…")
                extractDeb(file)
                file.delete()
            }
            check(prootBin.exists()) { "proot binary missing after extraction" }
            Os.chmod(prootBin.path, 0b111_000_000) // 0700
            prootLoader.takeIf { it.exists() }?.let { Os.chmod(it.path, 0b111_000_000) }

            step("resolving Alpine minirootfs…")
            val yaml = fetchText("$ALPINE_BASE/latest-releases.yaml")
            val rf = parseAlpineYaml(yaml) ?: error("minirootfs entry not found")
            step("downloading ${rf.first} (~4 MB)…")
            val tarball = File(sandboxDir, "rootfs.tar.gz")
            download("$ALPINE_BASE/${rf.first}", tarball)
            check(sha256(tarball) == rf.second) { "rootfs checksum mismatch" }

            step("extracting rootfs…")
            rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()
            extractTarGz(tarball, rootfsDir)
            tarball.delete()

            // Alpine points /bin/sh, /usr/bin/env, … at busybox; if it lands
            // without its exec bit every guest command fails with
            // `execve(...): Permission denied`. Force it (proot does the same).
            File(rootfsDir, "bin/busybox").takeIf { it.exists() }
                ?.let { runCatching { Os.chmod(it.path, 0b111_101_101) } } // 0755

            step("configuring…")
            File(rootfsDir, "etc/resolv.conf")
                .writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            File(rootfsDir, "etc/apk/repositories").writeText(
                "https://dl-cdn.alpinelinux.org/alpine/v$ALPINE_VERSION/main\n" +
                    "https://dl-cdn.alpinelinux.org/alpine/v$ALPINE_VERSION/community\n",
            )
            File(rootfsDir, "root").mkdirs()
            ensureShellProfile()

            marker.writeText("ok")
            _status.value = Status.Installed
        }.onFailure { e ->
            _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Where [hostPath] appears from inside the guest, and the bind (if any)
     * needed to put it there.
     *
     * A folder that already lives inside the rootfs — a repo cloned in the
     * terminal — is simply at its path relative to the rootfs, and binding it
     * over itself would make the guest see it at a second, duplicated path.
     * Anything else has to be bound in at the same path it has on the host.
     */
    fun guestLocationOf(hostPath: String): GuestLocation =
        guestLocationIn(rootfsDir, hostPath)

    /** A path as the guest sees it, plus the proot `-b` needed to expose it. */
    data class GuestLocation(val path: String, val bind: String?)

    /**
     * Give the guest shell the colour defaults a desktop distro ships with:
     * Alpine's busybox `sh` has a bare `$` prompt and no `ls` alias, which is
     * why the terminal came up as plain white text. Rewritten on every session
     * start so an Alpine installed by an earlier build gets it too.
     */
    fun ensureShellProfile() {
        if (!rootfsDir.isDirectory) return
        runCatching {
            val dir = File(rootfsDir, "etc/profile.d").apply { mkdirs() }
            File(dir, "kodelab-colour.sh").writeText(SHELL_PROFILE)
        }
    }

    private fun step(s: String) { _status.value = Status.Installing(s) }

    // --- download helpers ---

    private fun fetchText(url: String): String =
        open(url).use { it.readBytes().decodeToString() }

    private fun download(url: String, dest: File) {
        open(url).use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    private fun open(url: String): InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode} for $url" }
        return conn.inputStream
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(65536)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // --- archive extraction ---

    /** Pull the binaries from usr/bin and the shared libs from usr/lib out of a Termux .deb. */
    private fun extractDeb(deb: File) {
        ArArchiveInputStream(BufferedInputStream(deb.inputStream())).use { ar ->
            while (true) {
                val entry = ar.nextEntry ?: break
                if (!entry.name.startsWith("data.tar")) continue
                val decompressed: InputStream = when {
                    entry.name.endsWith(".xz") -> XZCompressorInputStream(ar)
                    entry.name.endsWith(".gz") -> GzipCompressorInputStream(ar)
                    else -> ar
                }
                val tar = TarArchiveInputStream(decompressed)
                val symlinks = mutableListOf<Pair<String, File>>() // linkName -> file
                while (true) {
                    val t = tar.nextEntry ?: break
                    if (t.isDirectory) continue
                    val name = t.name.removePrefix("./")
                    val rel = name.substringAfter("files/usr/", name.substringAfter("usr/", ""))
                    val target = when {
                        rel.startsWith("bin/") -> File(sandboxDir, rel)
                        rel.startsWith("lib/") && rel.contains(".so") -> File(sandboxDir, rel)
                        // proot's ptrace-injection helper — proot fails every guest
                        // exec without it (referenced via PROOT_LOADER).
                        rel.startsWith("libexec/") -> File(sandboxDir, rel)
                        else -> continue
                    }
                    target.parentFile?.mkdirs()
                    when {
                        // versioned .so files are symlinks (libtalloc.so.2 -> .so.2.4.3);
                        // recreate them so the dynamic linker can resolve SONAMEs.
                        t.isSymbolicLink -> symlinks += t.linkName to target
                        else -> {
                            target.outputStream().use { tar.copyTo(it) }
                            Os.chmod(target.path, 0b111_101_101) // 0755
                        }
                    }
                }
                symlinks.forEach { (link, file) ->
                    file.delete()
                    runCatching { Os.symlink(link, file.path) }
                }
                return
            }
        }
        error("data.tar not found in ${deb.name}")
    }

    private fun extractTarGz(tarball: File, into: File) {
        val links = mutableListOf<Pair<String, File>>() // linkTarget -> linkFile
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(tarball.inputStream()))).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./")
                if (name.isEmpty()) continue
                val target = File(into, name)
                // path-traversal guard
                if (!target.canonicalPath.startsWith(into.canonicalPath)) continue
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        runCatching { Os.symlink(entry.linkName, target.path) }
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        links += entry.linkName.removePrefix("./") to target
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { tar.copyTo(it) }
                        runCatching { Os.chmod(target.path, entry.mode and 0b111_111_111) }
                    }
                }
            }
        }
        // hardlinks second, once their targets exist
        links.forEach { (src, dst) ->
            runCatching { Os.link(File(into, src).path, dst.path) }
        }
    }

    // --- metadata parsing ---

    internal data class DebEntry(val name: String, val version: String, val filename: String, val sha256: String)

    companion object {
        /** A real escape character: Kotlin raw strings don't process \u escapes. */
        private const val ESC = "\u001B"

        /**
         * Where [hostPath] sits relative to [rootfs], as the guest sees it.
         *
         * Pure so a test can call *this* rather than a copy of it: the previous
         * version was mirrored in the test, and a bug that only existed in the
         * real code (a literal "${'$'}root/" that never matched) passed the suite
         * while every path fell through to the bind branch on the device.
         *
         * A directory has more than one true name — app storage is reachable as
         * both /data/user/0/<pkg> and /data/data/<pkg> — so both spellings of
         * each side are compared.
         */
        fun guestLocationIn(rootfs: File, hostPath: String): GuestLocation {
            val roots = buildList {
                add(rootfs.absolutePath.trimEnd('/'))
                runCatching { rootfs.canonicalPath.trimEnd('/') }.getOrNull()?.let { add(it) }
            }.distinct()
            val inputs = buildList {
                add(File(hostPath).absolutePath.trimEnd('/'))
                runCatching { File(hostPath).canonicalPath.trimEnd('/') }.getOrNull()?.let { add(it) }
            }.distinct()

            for (input in inputs) {
                for (root in roots) {
                    if (input == root) return GuestLocation("/", bind = null)
                    if (input.startsWith(root + "/")) {
                        return GuestLocation(input.removePrefix(root), bind = null)
                    }
                }
            }
            val outside = inputs.first()
            return GuestLocation(outside, bind = outside)
        }

        /** The profile text, exposed so a unit test can check its escapes. */
        fun shellProfileForTest(): String = SHELL_PROFILE

        private val SHELL_PROFILE = """
            # Colour defaults for the Kodelab terminal. Regenerated by the app on
            # every session start — edit ~/.profile instead if you want your own.
            export COLORTERM=truecolor
            export CLICOLOR=1

            # green user@host, blue cwd — the layout a Debian/Ubuntu .bashrc sets up
            PS1='${ESC}[1;32m\u@kodelab${ESC}[0m:${ESC}[1;34m\w${ESC}[0m\$ '

            alias ls='ls --color=auto'
            alias ll='ls -lah --color=auto'
            alias la='ls -A --color=auto'
            alias grep='grep --color=auto'
            alias egrep='egrep --color=auto'
            alias fgrep='fgrep --color=auto'
            alias diff='diff --color=auto'
            alias ip='ip -color=auto'

            # ls/dircolors palette: directories blue, symlinks cyan, archives red,
            # executables green — the familiar assignment, written out explicitly.
            export LS_COLORS='di=1;34:ln=1;36:so=1;35:pi=1;33:ex=1;32:bd=1;33:cd=1;33:su=1;31:sg=1;31:tw=1;34:ow=1;34:*.tar=1;31:*.tgz=1;31:*.zip=1;31:*.gz=1;31:*.bz2=1;31:*.xz=1;31:*.7z=1;31:*.jpg=1;35:*.png=1;35:*.gif=1;35:*.svg=1;35:*.mp3=1;36:*.mp4=1;36'
        """.trimIndent()

        private const val TERMUX_REPO = "https://packages.termux.dev/apt/termux-main"
        const val ALPINE_VERSION = "3.20"
        // not `const` — a const val can't interpolate another const
        private val ALPINE_BASE =
            "https://dl-cdn.alpinelinux.org/alpine/v$ALPINE_VERSION/releases/aarch64"

        /** Parse one package's stanza out of a Debian Packages index. */
        internal fun parseDebEntry(index: String, pkg: String): DebEntry? {
            val stanza = index.split("\n\n").firstOrNull { block ->
                block.lineSequence().firstOrNull() == "Package: $pkg" ||
                    block.lineSequence().any { it == "Package: $pkg" }
            } ?: return null
            fun field(key: String) = stanza.lineSequence()
                .firstOrNull { it.startsWith("$key: ") }?.removePrefix("$key: ")?.trim()
            return DebEntry(
                name = pkg,
                version = field("Version") ?: return null,
                filename = field("Filename") ?: return null,
                sha256 = field("SHA256") ?: return null,
            )
        }

        /** Find the minirootfs file + sha256 in Alpine's latest-releases.yaml. */
        internal fun parseAlpineYaml(yaml: String): Pair<String, String>? {
            val blocks = yaml.split(Regex("^-\\s", RegexOption.MULTILINE))
            val block = blocks.firstOrNull { it.contains("alpine-minirootfs") } ?: return null
            fun field(key: String) = Regex("$key:\\s*(\\S+)").find(block)?.groupValues?.get(1)
            val file = field("file") ?: return null
            val sha = field("sha256") ?: return null
            return file to sha
        }
    }
}
