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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.ui.home.ShareIntakeViewModel
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
     * PHASE 17: activity-scoped intake for URLs shared into NarcicTub. It
     * survives configuration changes, so a consumed share event is never
     * re-processed after a rotation; no static/global state is involved.
     */
    private val shareIntakeViewModel: ShareIntakeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Fresh creation only — on recreation the original intent was
        // already consumed (the intake ViewModel survived), so processing
        // again would duplicate the resolve.
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }
        setContent {
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            NarcicTubTheme(darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NarcicTubApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // App already running (foreground or background): each new share
        // intent is a deliberate user action and is processed once.
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            // No binary intake, no wildcard MIME: text/plain shares only.
            shareIntakeViewModel.onNewSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
        }
    }
}
