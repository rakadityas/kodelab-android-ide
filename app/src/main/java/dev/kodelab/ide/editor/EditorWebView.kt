package dev.kodelab.ide.editor

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import dev.kodelab.ide.theme.EditorPalette
import dev.kodelab.ide.workspace.WorkspacePresets
import org.json.JSONObject

private const val APP_ORIGIN = "https://appassets.androidplatform.net"

/** Thin controller so the rest of the app can push messages into the editor. */
class EditorController {
    internal var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun send(method: String, params: JSONObject = JSONObject()) {
        val payload = JSONObject().put("method", method).put("params", params).toString()
        // Bridge events arrive on the JavaBridge thread; WebView methods are main-only.
        mainHandler.post {
            webView?.evaluateJavascript(
                "window.__kodelab && window.__kodelab.receive($payload);", null,
            )
        }
    }

    fun applyTheme(palette: EditorPalette) {
        val tokens = JSONObject()
        palette.toWebTokens().forEach { (k, v) -> tokens.put(k, v) }
        send("theme.apply", JSONObject().put("tokens", tokens))
    }

    /** Reading controls from the workspace presets (REQ 2/8). */
    fun applySettings(presets: WorkspacePresets) {
        send(
            "settings.apply",
            JSONObject()
                .put("fontSize", presets.fontSizeSp)
                .put("lineHeight", presets.lineHeight.toDouble())
                .put("ligatures", presets.ligatures)
                .put("wordWrap", presets.wordWrap)
                .put("tabWidth", presets.tabWidth)
                .put("insertSpaces", presets.insertSpaces),
        )
    }

    fun openBuffer(tabId: String, text: String, languageId: String) =
        send(
            "buffer.open",
            JSONObject().put("tabId", tabId).put("text", text).put("languageId", languageId),
        )

    fun showBuffer(tabId: String) = send("buffer.show", JSONObject().put("tabId", tabId))
    fun revealLine(tabId: String, line: Int) =
        send("buffer.reveal", JSONObject().put("tabId", tabId).put("line", line))
    fun closeBuffer(tabId: String) = send("buffer.close", JSONObject().put("tabId", tabId))
    fun requestSave(tabId: String) = send("buffer.requestSave", JSONObject().put("tabId", tabId))
    fun markSaved(tabId: String) = send("buffer.markSaved", JSONObject().put("tabId", tabId))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditorWebView(
    controller: EditorController,
    palette: EditorPalette,
    onEvent: (method: String, params: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bridge = remember { EditorBridge(onEvent) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()

            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // Without explicit view focus the soft keyboard never opens for
                // Monaco's hidden textarea when the WebView sits inside Compose.
                isFocusable = true
                isFocusableInTouchMode = true
                setOnTouchListener { v, _ -> v.requestFocus(); false }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportZoom(false)
                    mediaPlaybackRequiresUserGesture = true
                }
                WebView.setWebContentsDebuggingEnabled(true)
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.d(
                            "KodelabWeb",
                            "${msg.messageLevel()} ${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}",
                        )
                        return true
                    }
                }
                addJavascriptInterface(bridge, EditorBridge.NAME)
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest) =
                        assetLoader.shouldInterceptRequest(request.url)

                    override fun onPageFinished(view: WebView, url: String) {
                        controller.applyTheme(palette)
                    }

                    @Deprecated("kept for < API 24 parity")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        !url.startsWith(APP_ORIGIN)
                }
                loadUrl("$APP_ORIGIN/assets/webapp/index.html")
                controller.webView = this
            }
        },
        update = {
            controller.webView = it
            controller.applyTheme(palette) // recomposes on theme change — keep Monaco in sync
        },
        onRelease = {
            it.removeJavascriptInterface(EditorBridge.NAME)
            it.destroy()
            controller.webView = null
        },
    )
}
