package dev.kodelab.ide.lsp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** One diagnostic (error/warning) for a document, in LSP 0-based coordinates. */
data class LspDiagnostic(
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val message: String,
    /** LSP severity: 1 Error, 2 Warning, 3 Information, 4 Hint. */
    val severity: Int,
    val source: String? = null,
)

data class PublishedDiagnostics(val uri: String, val diagnostics: List<LspDiagnostic>)

/**
 * A minimal Language Server Protocol client. Transport-agnostic: it hands framed
 * bytes to [send] and is driven by [receive]. Covers what an editor needs day
 * one — the initialize handshake, open/change document sync, and
 * publishDiagnostics — plus generic request/response plumbing for completion or
 * hover. Server-initiated requests are answered with a null result so servers
 * that expect a reply don't stall.
 */
class LspClient(private val send: (ByteArray) -> Unit) {

    private val decoder = LspFraming.Decoder()
    private val nextId = AtomicInteger(1)
    private val pending = HashMap<Int, (JSONObject?) -> Unit>()

    private val _diagnostics = MutableSharedFlow<PublishedDiagnostics>(extraBufferCapacity = 64)
    val diagnostics: SharedFlow<PublishedDiagnostics> = _diagnostics.asSharedFlow()

    private val _initialized = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)
    val initialized: SharedFlow<Boolean> = _initialized.asSharedFlow()

    /** Feed raw bytes from the transport; complete messages are dispatched. */
    fun receive(bytes: ByteArray, len: Int = bytes.size) {
        for (payload in decoder.feed(bytes, len)) dispatch(LspRpc.parse(payload))
    }

    // --- lifecycle ---

    fun initialize(rootUri: String?) {
        val params = JSONObject()
            .put("processId", JSONObject.NULL)
            .put("rootUri", rootUri ?: JSONObject.NULL)
            .put("capabilities", clientCapabilities())
        request("initialize", params) {
            notify("initialized", JSONObject())
            _initialized.tryEmit(true)
        }
    }

    fun didOpen(uri: String, languageId: String, version: Int, text: String) {
        notify(
            "textDocument/didOpen",
            JSONObject().put(
                "textDocument",
                JSONObject().put("uri", uri).put("languageId", languageId)
                    .put("version", version).put("text", text),
            ),
        )
    }

    fun didChange(uri: String, version: Int, text: String) {
        // full-document sync (simplest, widely supported)
        notify(
            "textDocument/didChange",
            JSONObject()
                .put("textDocument", JSONObject().put("uri", uri).put("version", version))
                .put("contentChanges", JSONArray().put(JSONObject().put("text", text))),
        )
    }

    fun didClose(uri: String) {
        notify("textDocument/didClose", JSONObject().put("textDocument", JSONObject().put("uri", uri)))
    }

    fun shutdown() {
        request("shutdown", null) { notify("exit", null) }
    }

    // --- requests ---

    /** Generic request; [onResult] is invoked with the `result` object (or null). */
    fun request(method: String, params: JSONObject?, onResult: (JSONObject?) -> Unit = {}) {
        val id = nextId.getAndIncrement()
        pending[id] = onResult
        send(LspFraming.encode(LspRpc.request(id, method, params)))
    }

    fun notify(method: String, params: JSONObject?) {
        send(LspFraming.encode(LspRpc.notification(method, params)))
    }

    // --- incoming ---

    private fun dispatch(msg: LspRpc.Incoming) {
        when (msg.kind) {
            LspRpc.Kind.RESPONSE -> msg.id?.let { pending.remove(it)?.invoke(msg.result) }
            LspRpc.Kind.ERROR -> msg.id?.let { pending.remove(it)?.invoke(null) }
            LspRpc.Kind.REQUEST ->
                // Answer server requests (configuration, registerCapability, …) minimally.
                msg.id?.let { send(LspFraming.encode(LspRpc.response(it, null))) }
            LspRpc.Kind.NOTIFICATION ->
                if (msg.method == "textDocument/publishDiagnostics") {
                    msg.params?.let { _diagnostics.tryEmit(parseDiagnostics(it)) }
                }
            LspRpc.Kind.UNKNOWN -> {}
        }
    }

    private fun clientCapabilities(): JSONObject =
        JSONObject().put(
            "textDocument",
            JSONObject()
                .put("synchronization", JSONObject().put("didSave", false).put("dynamicRegistration", false))
                .put("publishDiagnostics", JSONObject().put("relatedInformation", false))
                .put(
                    "completion",
                    JSONObject().put("completionItem", JSONObject().put("snippetSupport", false)),
                ),
        )

    companion object {
        fun parseDiagnostics(params: JSONObject): PublishedDiagnostics {
            val uri = params.optString("uri")
            val arr = params.optJSONArray("diagnostics") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                val range = d.optJSONObject("range") ?: return@mapNotNull null
                val start = range.optJSONObject("start") ?: return@mapNotNull null
                val end = range.optJSONObject("end") ?: start
                LspDiagnostic(
                    startLine = start.optInt("line"),
                    startChar = start.optInt("character"),
                    endLine = end.optInt("line"),
                    endChar = end.optInt("character"),
                    message = d.optString("message"),
                    severity = d.optInt("severity", 1),
                    source = d.optString("source").ifBlank { null },
                )
            }
            return PublishedDiagnostics(uri, list)
        }
    }
}
