package com.narcictub.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.narcictub.app.MainActivity
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.usecase.ObserveDownloadsUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * PHASE 21 — Android glue for download notifications, driven entirely by
 * the REAL persistent download state (Room history + real byte progress
 * from the existing orchestrator). No worker, no second downloader, no
 * alternate network path: the existing bounded download orchestrator keeps
 * running (documented decision — WorkManager 2.11.2 requires
 * minCompileSdk 35 / AGP 8.6.0 vs this project's 34/8.5.2 and does not
 * resolve offline; see the Phase 21 report), and this controller renders
 * its persistent state to the user.
 *
 * SAFETY:
 *  - channel created once, idempotently, IMPORTANCE_LOW (no sound/vibration)
 *  - notification id == persistent history id (stable correlation)
 *  - snapshots contain only sanitized titles + real progress — no URLs,
 *    tokens, query strings or paths (enforced by the pure model)
 *  - contentIntent opens the app (explicit launcher intent, FLAG_IMMUTABLE)
 *  - no notification actions: pause/resume/cancel stay in the app for now —
 *    decorative buttons are not exposed (spec §22)
 *  - notifications are skipped entirely when notifications are disabled
 *    (API 33+ runtime permission denied) — downloads keep working and the
 *    in-app state remains the source of truth
 */
@Singleton
class DownloadNotificationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val observeDownloads: ObserveDownloadsUseCase,
    private val workScope: CoroutineScope,
) {

    private val rendered = mutableMapOf<Long, DownloadNotificationSnapshot>()

    /** Starts observing real download state. App-lifetime, runs once. */
    fun start() {
        workScope.launch {
            ensureChannel(context)
            observeDownloads().collect { overview ->
                render(overview.isLoading, overview.items.map { item ->
                    Triple(item.id, item, overview.progress[item.id])
                })
            }
        }
    }

    internal fun render(
        isLoading: Boolean,
        rows: List<Triple<Long, com.narcictub.app.domain.model.HistoryItem, com.narcictub.app.domain.model.DownloadProgress?>>,
    ) {
        if (isLoading) return // wait for the first real snapshot
        if (!isNotificationEnabled()) {
            rendered.clear()
            return // graceful degradation — in-app state remains authoritative
        }

        val manager = NotificationManagerCompat.from(context)
        val currentIds = mutableSetOf<Long>()
        for ((id, item, progress) in rows) {
            currentIds.add(id)
            val snapshot = DownloadNotifications.build(id, item, progress) ?: continue
            if (DownloadNotifications.decide(seenBefore = rendered.containsKey(id), snapshot = snapshot) ==
                NotificationAction.SKIP
            ) {
                // Record without posting — baseline terminal rows from a
                // previous session must not re-notify (spec §16).
                rendered[id] = snapshot
                continue
            }
            if (DownloadNotifications.shouldNotify(rendered[id], snapshot)) {
                manager.notify(snapshot.id.toInt(), buildNotification(snapshot))
                rendered[id] = snapshot
            }
        }
        // Rows removed from history (user removal / bulk clear) dismiss
        // their notifications — notification state follows persistence.
        val removed = rendered.keys.filterNot { it in currentIds }
        for (id in removed) {
            manager.cancel(id.toInt())
            rendered.remove(id)
        }
    }

    private fun buildNotification(snapshot: DownloadNotificationSnapshot): Notification {
        val builder = NotificationCompat.Builder(context, DownloadNotifications.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done.takeIf {
                snapshot.status == DownloadStatus.COMPLETED
            } ?: android.R.drawable.stat_sys_download)
            .setContentTitle(snapshot.title)
            .setContentText(snapshot.text)
            .setOnlyAlertOnce(true)
            .setSilent(true) // progress updates must not buzz — IMPORTANCE_LOW + silent
            .setOngoing(snapshot.ongoing)
            .setContentIntent(contentIntent())

        if (snapshot.status == DownloadStatus.COMPLETED) {
            builder.setProgress(100, 100, false)
        } else {
            val percent = snapshot.progressPercent
            if (percent != null) {
                builder.setProgress(100, percent, false)
            } else {
                builder.setProgress(0, 0, true) // real indeterminate — unknown total
            }
        }
        if (!snapshot.ongoing) builder.setAutoCancel(true)
        return builder.build()
    }

    /** Explicit launcher intent — no implicit intents, immutable flags. */
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun isNotificationEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    companion object {
        /**
         * Creates the downloads channel once per process — safe to call
         * repeatedly (idempotent, IMPORTANCE_LOW, no sound/vibration).
         */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                DownloadNotifications.CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress of your downloads"
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
