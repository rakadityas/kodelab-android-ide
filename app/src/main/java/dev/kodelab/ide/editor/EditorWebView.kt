package dev.kodelab.ide.editor

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import dev.kodelab.ide.lsp.LspDiagnostic
import dev.kodelab.ide.theme.CodeScheme
import dev.kodelab.ide.theme.EditorPalette
import dev.kodelab.ide.workspace.WorkspacePresets
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

private const val APP_ORIGIN = "https://appassets.androidplatform.net"

/**
 * Focus the WebView on a *tap*, never mid-scroll.
 *
 * Requesting focus while a finger is dragging makes Monaco focus its hidden
 * textarea, and the browser then scrolls that textarea into view — the editor
 * jumping back up by a few pixels part-way through a scroll. Only a touch that
 * ends without travelling further than the system's touch slop counts as a tap.
 */
private class TapToFocus(
    private val enabled: () -> Boolean,
    private val onTap: () -> Unit,
) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var slop = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (slop < 0) slop = ViewConfiguration.get(v.context).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
            MotionEvent.ACTION_UP -> {
                val moved = hypot(event.x - downX, event.y - downY)
                if (moved <= slop) {
                    // A tap on the code means "I'm working here now" — the side
                    // panel gets out of the way whether or not we take focus.
                    onTap()
                    if (enabled()) v.requestFocus()
                }
            }
        }
        return false // never consume: Monaco still gets the gesture
    }
}

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

    /**
     * [palette] dresses the page around the editor; [scheme], when given, is the
     * code area's own colours and syntax rules (see CodeScheme).
     */
    fun applyTheme(palette: EditorPalette, scheme: CodeScheme? = null) {
        val tokens = JSONObject()
        palette.toWebTokens().forEach { (k, v) -> tokens.put(k, v) }
        val params = JSONObject().put("tokens", tokens)
        scheme?.let { params.put("code", it.toWebTheme().toJson()) }
        send("theme.apply", params)
    }

    private fun Map<String, Any>.toJson(): JSONObject {
        val out = JSONObject()
        forEach { (k, v) ->
            when (v) {
                is List<*> -> {
                    val arr = JSONArray()
                    v.filterIsInstance<Map<*, *>>().forEach { rule ->
                        val o = JSONObject()
                        rule.forEach { (rk, rv) -> o.put(rk.toString(), rv) }
                        arr.put(o)
                    }
                    out.put(k, arr)
                }
                else -> out.put(k, v)
            }
        }
        return out
    }

    /** Reading controls from the workspace presets (REQ 2/8). */
    fun applySettings(presets: WorkspacePresets) {
        send(
            "settings.apply",
            JSONObject()
                .put("fontSize", presets.fontSizeSp)
                .put("fontFamily", presets.fontFamily)
                .put("lineHeight", presets.lineHeight.toDouble())
                .put("ligatures", presets.ligatures)
                .put("wordWrap", presets.wordWrap)
                .put("tabWidth", presets.tabWidth)
                .put("insertSpaces", presets.insertSpaces)
                .put("selectionDelayMs", presets.selectionDelayMs)
                // reading mode -> Monaco read-only, so the soft keyboard never opens
                .put("readOnly", !presets.editMode),
        )
    }

    fun openBuffer(tabId: String, text: String, languageId: String) =
        send(
            "buffer.open",
            JSONObject().put("tabId", tabId).put("text", text).put("languageId", languageId),
        )

    /** Render LSP diagnostics as Monaco markers on a tab (converts to 1-based coords + severity). */
    fun pushDiagnostics(tabId: String, diagnostics: List<LspDiagnostic>) {
        val markers = JSONArray()
        diagnostics.forEach { d ->
            markers.put(
                JSONObject()
                    .put("startLineNumber", d.startLine + 1).put("startColumn", d.startChar + 1)
                    .put("endLineNumber", d.endLine + 1).put("endColumn", d.endChar + 1)
                    .put("message", d.message)
                    .put("severity", monacoSeverity(d.severity))
                    .put("source", d.source ?: "lsp"),
            )
        }
        send("lsp.diagnostics", JSONObject().put("tabId", tabId).put("markers", markers))
    }

    /** LSP severity (1 Error…4 Hint) -> Monaco MarkerSeverity (Error 8, Warning 4, Info 2, Hint 1). */
    private fun monacoSeverity(lsp: Int): Int = when (lsp) {
        1 -> 8
        2 -> 4
        3 -> 2
        else -> 1
    }

    fun showBuffer(tabId: String) = send("buffer.show", JSONObject().put("tabId", tabId))
    fun revealLine(tabId: String, line: Int) =
        send("buffer.reveal", JSONObject().put("tabId", tabId).put("line", line))
    /** Insert a snippet (with $1/$0 tab stops) at the cursor via Monaco. */
    fun insertSnippet(snippet: String) =
        send("input.snippet", JSONObject().put("snippet", snippet))
    fun closeBuffer(tabId: String) = send("buffer.close", JSONObject().put("tabId", tabId))
    fun requestSave(tabId: String) = send("buffer.requestSave", JSONObject().put("tabId", tabId))
    /** Ask the editor to post the selected symbol back under [reply]. */
    fun requestSymbol(reply: String) =
        send("editor.requestSymbol", JSONObject().put("reply", reply))

    fun markSaved(tabId: String) = send("buffer.markSaved", JSONObject().put("tabId", tabId))

    /** Drop [text] in at the cursor, replacing the selection (the paste path). */
    fun insertText(text: String) = send("input.type", JSONObject().put("text", text))

    /** Jump the view to the start or end of the file. */
    fun scrollTo(where: String) = send("buffer.scrollTo", JSONObject().put("where", where))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditorWebView(
    controller: EditorController,
    palette: EditorPalette,
    codeScheme: CodeScheme? = null,
    onEvent: (method: String, params: String) -> Unit,
    modifier: Modifier = Modifier,
    editMode: Boolean = true,
    /** A tap on the editor body (not a scroll) — used to dismiss the sidebar. */
    onTap: () -> Unit = {},
) {
    val bridge = remember { EditorBridge(onEvent) }
    // The factory runs once, so the touch listener reads the mode through a
    // holder that recomposition keeps current rather than capturing it.
    val editModeRef = remember { mutableStateOf(editMode) }
    editModeRef.value = editMode
    val onTapRef = remember { mutableStateOf(onTap) }
    onTapRef.value = onTap
    val schemeRef = remember { mutableStateOf(codeScheme) }
    schemeRef.value = codeScheme

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
                // Only pull view focus (which raises the keyboard) in edit
                // mode, and only on a tap — see TapToFocus.
                setOnTouchListener(TapToFocus({ editModeRef.value }, { onTapRef.value() }))
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
                        controller.applyTheme(palette, schemeRef.value)
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
            controller.applyTheme(palette, codeScheme) // recomposes on theme change — keep Monaco in sync
            if (!editMode) {
                it.clearFocus()
                val imm = it.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(it.windowToken, 0)
            }
        },
        onRelease = {
            it.removeJavascriptInterface(EditorBridge.NAME)
            it.destroy()
            controller.webView = null
        },
    )
}
