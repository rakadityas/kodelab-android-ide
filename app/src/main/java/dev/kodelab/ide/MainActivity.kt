package dev.kodelab.ide

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    IdeEvent.OpenFolderPicker -> folderPicker.launch(null)
                    IdeEvent.NewWindow -> openNewWindow()
                    IdeEvent.ImportThemeFile ->
                        themeFilePicker.launch(arrayOf("application/json", "text/plain", "*/*"))
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

    /** Another Kodelab window as its own task — split-screen / DeX / ChromeOS ready. */
    private fun openNewWindow() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT,
            ),
        )
    }
}
