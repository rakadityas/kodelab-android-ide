package dev.kodelab.ide

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.kodelab.ide.theme.KodelabTheme
import dev.kodelab.ide.ui.IdeEvent
import dev.kodelab.ide.ui.IdeScaffold
import dev.kodelab.ide.ui.IdeViewModel
import dev.kodelab.ide.workspace.WorkspaceRepository
import kotlinx.coroutines.launch

/**
 * One [MainActivity] instance == one Kodelab window (REQ 7).
 * Each instance binds one workspace and applies that workspace's presets (REQ 8);
 * device-wide services (terminal, settings) are shared singletons.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        /** Set on windows launched from the app, which skip the session restore. */
        const val EXTRA_FRESH_WINDOW = "dev.kodelab.ide.FRESH_WINDOW"
    }

    private val viewModel: IdeViewModel by viewModels {
        val app = application as KodelabApp
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                IdeViewModel(app.settings, WorkspaceRepository(app)) as T
        }
    }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(viewModel::openFolder)
        }

    private val themeFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importThemeFrom)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A cold start comes back to the folder and files you left open; a window
        // opened from inside the app (REQ 7) deliberately starts empty.
        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_FRESH_WINDOW, false)) {
            viewModel.restoreSession()
        }

        // The editor's copy/paste run through the system clipboard service,
        // which lives here: a WebView's own clipboard access is unreliable and
        // wouldn't be the clipboard the rest of the phone shares.
        val clipboard = getSystemService(ClipboardManager::class.java)
        viewModel.clipboard = { text ->
            runCatching { clipboard?.setPrimaryClip(ClipData.newPlainText("Kodelab", text)) }
        }
        viewModel.pasteRequest = {
            val text = clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            viewModel.pasteIntoEditor(text ?: "")
        }

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    IdeEvent.OpenFolderPicker -> folderPicker.launch(null)
                    IdeEvent.NewWindow -> openNewWindow()
                    IdeEvent.ImportThemeFile ->
                        themeFilePicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                    is IdeEvent.OpenUrl -> openExternal(event.url)
                }
            }
        }

        setContent {
            val uiState by viewModel.state.collectAsState()
            val customPalettes = uiState.customThemes.associate { it.id to it.palette }
            KodelabTheme(themeId = uiState.presets.themeId, custom = customPalettes) {
                IdeScaffold(state = uiState, actions = viewModel, viewModel = viewModel)
            }
        }
    }

    /** Hand a link from the Markdown reader to the system browser/mail client. */
    private fun openExternal(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { viewModel.reportStatus("No app can open $url") }
    }

    /** Another Kodelab window as its own task — split-screen / DeX / ChromeOS ready. */
    private fun openNewWindow() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT,
            ).putExtra(EXTRA_FRESH_WINDOW, true),
        )
    }
}
