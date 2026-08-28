package dev.kodelab.ide.lsp

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * The Language Server Protocol wire format: `Content-Length` framing over a
 * byte stream, plus minimal JSON-RPC 2.0 helpers. Pure and unit-testable — no
 * Android, no transport — so the tricky framing (partial reads, several
 * messages in one buffer) is verified off-device.
 */
object LspFraming {

    private const val CRLF = "\r\n"

    /** Frame a JSON payload as `Content-Length: N\r\n\r\n<utf8 body>`. */
    fun encode(payload: String): ByteArray {
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${body.size}$CRLF$CRLF".toByteArray(StandardCharsets.US_ASCII)
        return header + body
    }

    /**
     * Incremental decoder: [feed] accepts whatever bytes arrived and returns any
     * complete JSON payloads it can now extract, buffering the remainder.
     */
    class Decoder {
        private var acc = ByteArray(0)

        fun feed(bytes: ByteArray, len: Int = bytes.size): List<String> {
            if (len > 0) acc += bytes.copyOfRange(0, len)
            val out = mutableListOf<String>()
            while (true) {
                val headerEnd = indexOfDoubleCrlf(acc)
                if (headerEnd < 0) break
                val header = String(acc, 0, headerEnd, StandardCharsets.US_ASCII)
                val length = contentLength(header) ?: run {
                    // Unparseable header — drop it so we don't wedge forever.
                    acc = acc.copyOfRange(headerEnd + 4, acc.size)
                    return@run -1
                }
                if (length < 0) continue
                val bodyStart = headerEnd + 4
                if (acc.size - bodyStart < length) break // body not fully arrived
                val body = String(acc, bodyStart, length, StandardCharsets.UTF_8)
                out += body
                acc = acc.copyOfRange(bodyStart + length, acc.size)
            }
            return out
        }

        private fun indexOfDoubleCrlf(b: ByteArray): Int {
            var i = 0
            while (i + 3 < b.size) {
                if (b[i] == CR && b[i + 1] == LF && b[i + 2] == CR && b[i + 3] == LF) return i
                i++
            }
            return -1
        }

        private fun contentLength(header: String): Int? =
            header.split(CRLF).firstNotNullOfOrNull { line ->
                val idx = line.indexOf(':')
                if (idx < 0) return@firstNotNullOfOrNull null
                if (!line.substring(0, idx).trim().equals("Content-Length", true)) return@firstNotNullOfOrNull null
                line.substring(idx + 1).trim().toIntOrNull()
            }

        private companion object {
            const val CR = '\r'.code.toByte()
            const val LF = '\n'.code.toByte()
        }
    }
}

/** Minimal JSON-RPC 2.0 message construction and classification for LSP. */
object LspRpc {

    fun request(id: Int, method: String, params: JSONObject?): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
            .apply { params?.let { put("params", it) } }
            .toString()

    fun notification(method: String, params: JSONObject?): String =
        JSONObject().put("jsonrpc", "2.0").put("method", method)
            .apply { params?.let { put("params", it) } }
            .toString()

    fun response(id: Int, result: JSONObject?): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result ?: JSONObject()).toString()

    enum class Kind { REQUEST, RESPONSE, NOTIFICATION, ERROR, UNKNOWN }

    data class Incoming(
        val kind: Kind,
        val id: Int?,
        val method: String?,
        val raw: JSONObject,
    ) {
        val params: JSONObject? get() = raw.optJSONObject("params")
        val result: JSONObject? get() = raw.optJSONObject("result")
    }

    fun parse(payload: String): Incoming {
        val o = runCatching { JSONObject(payload) }.getOrElse {
            return Incoming(Kind.UNKNOWN, null, null, JSONObject())
        }
        val hasId = o.has("id") && !o.isNull("id")
        val id = if (hasId) o.optInt("id") else null
        val method = o.optString("method").ifBlank { null }
        val kind = when {
            o.has("error") -> Kind.ERROR
            method != null && hasId -> Kind.REQUEST
            method != null -> Kind.NOTIFICATION
            hasId -> Kind.RESPONSE
            else -> Kind.UNKNOWN
        }
        return Incoming(kind, id, method, o)
    }
}
