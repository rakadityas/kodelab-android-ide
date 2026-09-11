package dev.kodelab.ide.git

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * A reader for git's `.git/index` file.
 *
 * Needed because a folder shared from another app has no filesystem path, so
 * the sandbox's git can't be pointed at it — but the index can still be read
 * through the content provider, which is enough to say what has changed.
 *
 * Format (v2/v3): a "DIRC" header, then one entry per tracked path carrying the
 * stat data git cached and the blob's SHA-1, each padded to an 8-byte boundary.
 * v4 path-compresses and is rejected rather than half-parsed.
 */
object GitIndex {

    data class Entry(val path: String, val size: Long, val sha: String)

    class UnsupportedIndex(message: String) : Exception(message)

    fun parse(bytes: ByteArray): List<Entry> {
        if (bytes.size < 12) throw UnsupportedIndex("index too short")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }.decodeToString()
        if (magic != "DIRC") throw UnsupportedIndex("not a git index")
        val version = buf.int
        if (version !in 2..3) throw UnsupportedIndex("index version $version")
        val count = buf.int
        if (count < 0 || count > 500_000) throw UnsupportedIndex("implausible entry count")

        val out = ArrayList<Entry>(count)
        repeat(count) {
            val start = buf.position()
            buf.position(start + 36)           // ctime, mtime, dev, ino, mode, uid, gid
            val size = buf.int.toLong() and 0xFFFFFFFFL
            val sha = ByteArray(20).also { buf.get(it) }.joinToString("") { b -> "%02x".format(b) }
            val flags = buf.short.toInt() and 0xFFFF
            if (version == 3 && (flags and 0x4000) != 0) buf.short // extended flags
            val nameLen = flags and 0x0FFF
            val path = if (nameLen < 0x0FFF) {
                ByteArray(nameLen).also { buf.get(it) }.decodeToString()
            } else {
                // 0xFFF means "length doesn't fit" — read to the NUL instead
                val sb = StringBuilder()
                while (true) {
                    val c = buf.get()
                    if (c.toInt() == 0) break
                    sb.append(c.toInt().toChar())
                }
                sb.toString()
            }
            out += Entry(path, size, sha)
            // entries are NUL-padded to a multiple of 8 bytes from their start
            val consumed = buf.position() - start
            val padded = ((consumed + 8) / 8) * 8
            buf.position(start + padded)
        }
        return out
    }

    /**
     * The SHA-1 git would give this content: `blob <len>\0` then the bytes.
     * Comparing it to the index entry says whether a file really changed,
     * without trusting the cached mtime.
     */
    fun blobSha(content: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${content.size}".toByteArray())
        md.update(0)
        md.update(content)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The branch named by a `.git/HEAD`, or null when HEAD is detached. */
    fun branchFromHead(head: String): String? {
        val t = head.trim()
        return if (t.startsWith("ref:")) t.removePrefix("ref:").trim().removePrefix("refs/heads/")
        else null
    }
}
