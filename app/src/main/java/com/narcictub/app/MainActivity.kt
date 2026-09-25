package com.narcictub.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.ui.navigation.NarcicTubApp
import com.narcictub.app.ui.settings.SettingsViewModel
import com.narcictub.app.ui.theme.NarcicTubTheme
import com.narcictub.app.ui.theme.resolveDarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * PHASE 13: the persisted theme mode drives the REAL app theme. The
     * ViewModel projects the DataStore-backed setting — no duplicated state.
     */
    private val settingsViewModel: SettingsViewModel by viewModels()

    /**
     * One-shot request to land on the Downloads tab — set when launched (or
     * re-launched) by the share dialog's "Go to downloads" button. Consumed
     * by NarcicTubApp after navigating; stays false for normal launches.
     */
    private var openDownloads by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openDownloads = intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true
        setContent {
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            NarcicTubTheme(darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NarcicTubApp(
                        openDownloadsFirst = openDownloads,
                        onDownloadsOpened = { openDownloads = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Already running: "Go to downloads" from the share dialog must
        // switch the live instance to the Downloads tab.
        if (intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) {
            openDownloads = true
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
