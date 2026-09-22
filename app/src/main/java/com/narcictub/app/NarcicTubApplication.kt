package com.narcictub.app

import android.app.Application
import com.narcictub.app.domain.usecase.RecoverInterruptedDownloadsUseCase
import com.narcictub.app.notify.DownloadNotificationController
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App entry point. On startup, recovers rows a previous process left in a
 * non-terminal state (QUEUED/DOWNLOADING/PAUSED → FAILED "Interrupted by
 * app restart"): no jobs survive process death, so without this the UI
 * would show permanently "downloading" ghosts. Runs exactly once per
 * process on the existing app-lifetime download work scope (see DataModule)
 * — no scope of its own is created; the use case is idempotent anyway.
 *
 * PHASE 21: the same scope also drives the download notification renderer,
 * which projects the REAL persistent download state (Room + real byte
 * progress) to the user. Channel creation is idempotent; notifications
 * degrade gracefully when the platform notification permission is denied.
 */
@HiltAndroidApp
class NarcicTubApplication : Application() {

    @Inject lateinit var recoverInterrupted: RecoverInterruptedDownloadsUseCase

    // App-lifetime scope provided by DataModule (SupervisorJob + Default).
    @Inject lateinit var downloadWorkScope: CoroutineScope

    @Inject lateinit var downloadNotifications: DownloadNotificationController

    override fun onCreate() {
        super.onCreate()
        DownloadNotificationController.ensureChannel(this)
        downloadWorkScope.launch { recoverInterrupted() }
        downloadNotifications.start()
    }
}
