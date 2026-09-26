package com.narcictub.app.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.narcictub.app.R
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.repository.HistoryRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the PROCESS alive while downloads are in
 * flight. This is what makes "download continues in the background" real:
 * queueing happens in the app-scoped orchestrator (in this same process),
 * and without a foreground service Android kills an empty process shortly
 * after the share dialog (or the activity) goes away — which used to kill
 * queued downloads and silence notifications.
 *
 * Lifecycle:
 *  - started by the download orchestrator whenever a row is enqueued
 *    (share dialog, Home form, retry) — always from a visible activity, so
 *    the Android 12+ foreground-service start restriction never applies
 *  - promotes itself to foreground with a quiet summary notification on the
 *    shared downloads channel; per-row progress notifications keep coming
 *    from [DownloadNotificationController] as before
 *  - watches the REAL persistent history and stops itself the moment no row
 *    is QUEUED / DOWNLOADING / PAUSED anymore — the service follows the
 *    orchestrator's state, never inventing its own notion of "busy"
 */
@AndroidEntryPoint
class DownloadForegroundService : Service() {

    @Inject lateinit var historyRepository: HistoryRepository

    // App-lifetime scope (DataModule) — the watch job dies with the service.
    @Inject lateinit var workScope: CoroutineScope

    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        watchJob = workScope.launch {
            historyRepository.observeHistory().collect { rows ->
                if (rows.none { DownloadNotifications.isInFlight(it.status) }) {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.download_in_progress))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(DownloadNotificationOpen.pendingIntent(this))
            .build()
        ServiceCompat.startForeground(
            this,
            SUMMARY_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        watchJob = null
        super.onDestroy()
    }

    companion object {
        const val SUMMARY_NOTIFICATION_ID = 1001

        /** Started by the orchestrator on every enqueue — idempotent. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadForegroundService::class.java))
        }
    }
}
