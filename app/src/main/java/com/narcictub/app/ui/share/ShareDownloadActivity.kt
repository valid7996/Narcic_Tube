package com.narcictub.app.ui.share

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.MainActivity
import com.narcictub.app.ui.theme.NarcicTubTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Share-target activity: a SMALL floating dialog shown over the app the
 * user shared from (YouTube → Share → NarcicTub). Declared in the manifest
 * with a translucent floating-dialog theme, excluded from Recents and
 * finished when it leaves sight — so dismissing it drops the user straight
 * back into the sharing app, exactly where they were.
 *
 * The window wraps the compact sheet content (it never fills the screen),
 * so a touch outside it dismisses the dialog. The chosen download is queued
 * through the app-scoped repository/worker scope, so it keeps running after
 * this dialog (or the whole task) closes; progress is visible through the
 * download notification and the app's Downloads tab.
 */
@AndroidEntryPoint
class ShareDownloadActivity : ComponentActivity() {

    private val viewModel: ShareDownloadViewModel by viewModels()

    /**
     * Notifications are requested the moment the user acts ("Download" tap)
     * — never at open — so progress/completion notifications can appear for
     * the background download. A denial changes nothing: the download still
     * runs and in-app state remains the source of truth.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fresh creation only — on recreation the ViewModel (which survived)
        // already holds the resolve result; re-feeding the text would restart
        // the resolve for nothing.
        if (savedInstanceState == null && intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            viewModel.onSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))
        }

        setContent {
            val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            NarcicTubTheme(darkTheme = isDarkTheme) {
                // Bounded max size keeps the floating window content-sized and
                // centered; everything outside it belongs to the sharing app.
                ShareDownloadSheet(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 48.dp),
                    onGoToDownloads = ::openDownloadsTab,
                    onDismiss = ::finish,
                    state = state,
                    onVariantSelected = viewModel::onVariantSelected,
                    onDownload = ::requestNotificationsAndDownload,
                    onRetry = viewModel::retry,
                )
            }
        }
    }

    private fun requestNotificationsAndDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.onDownloadSelected()
    }

    /** "Go to downloads": opens the main app directly on the Downloads tab. */
    private fun openDownloadsTab() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            },
        )
        finish()
    }
}
