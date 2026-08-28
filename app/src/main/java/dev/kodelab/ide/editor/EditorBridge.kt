package dev.kodelab.ide.editor

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Native side of the editor bridge. The web app calls
 * `KodelabHost.post(JSON.stringify({method, params}))`; Kotlin routes it.
 *
 * Only @JavascriptInterface-annotated methods are reachable from JS (API 17+ rule),
 * and the WebView only loads our own bundled assets over https://appassets.androidplatform.net,
 * so no third-party page can reach this object.
 */
class EditorBridge(
    private val onEvent: (method: String, params: String) -> Unit,
) {
    @JavascriptInterface
    fun post(message: String) {
        val obj = runCatching { JSONObject(message) }.getOrNull() ?: return
        val method = obj.optString("method").ifBlank { return }
        val params = obj.optJSONObject("params")?.toString() ?: "{}"
        onEvent(method, params)
    }

    companion object {
        const val NAME = "KodelabHost"
    }
}
