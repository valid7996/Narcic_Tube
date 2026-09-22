package com.narcictub.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.narcictub.app.MainActivity

/**
 * Optional foreground service (Settings → "Suggest downloads from
 * clipboard"): watches for a new YouTube/Instagram/direct-media link copied
 * to the clipboard and opens the floating bubble for it — the same
 * suggest-and-get-out-of-the-way flow as sharing a link, just triggered by
 * copy instead of the Share button.
 *
 * PLATFORM LIMIT (be upfront about this, don't pretend otherwise): since
 * Android 10, an app in the background is generally not allowed to read the
 * clipboard's content — only the foreground app / current input method can.
 * [ClipboardManager.OnPrimaryClipChangedListener] still fires for this
 * service, but [ClipboardManager.getPrimaryClip] can come back empty while
 * NarcicTub is not the foreground/focused app, purely depending on the
 * device and Android version. There is no reliable app-level workaround
 * that does not require the much heavier AccessibilityService permission,
 * which this app deliberately does not request. In practice this works
 * best right after using NarcicTub or on older devices; the in-app
 * clipboard suggestion on the Home screen (which always works, since the
 * app is foreground then) is the reliable fallback.
 *
 * Runs only while explicitly enabled (persisted setting); never starts
 * itself, never reads the clipboard outside this listener, and never logs
 * or stores clipboard content — a detected link only ever becomes a
 * [OverlayBubbleService] request.
 */
class ClipboardWatcherService : Service() {

    private lateinit var clipboardManager: ClipboardManager
    private var lastHandledText: String? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = runCatching {
            clipboardManager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
        }.getOrNull()
        if (text.isNullOrBlank() || text == lastHandledText) return@OnPrimaryClipChangedListener
        lastHandledText = text
        val url = BubbleUrlIntake.singleUrlOrNull(text) ?: return@OnPrimaryClipChangedListener
        if (OverlayPermission.isGranted(this)) {
            OverlayBubbleService.showForUrl(this, url)
        } else {
            notifyPermissionNeeded()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(ClipboardManager::class.java)
        startForeground(NOTIFICATION_ID, buildNotification())
        clipboardManager.addPrimaryClipChangedListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { clipboardManager.removePrimaryClipChangedListener(listener) }
        super.onDestroy()
    }

    private fun notifyPermissionNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, OverlayPermission.requestIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, PROMPT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Link copied")
            .setContentText("Allow \"display over other apps\" to get a download bubble.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager?.notify(PROMPT_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Clipboard link watcher", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) },
            )
            manager?.createNotificationChannel(
                NotificationChannel(PROMPT_CHANNEL_ID, "Clipboard link found", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Watching clipboard for YouTube/Instagram links")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "clipboard_watcher"
        private const val PROMPT_CHANNEL_ID = "clipboard_watcher_prompt"
        private const val NOTIFICATION_ID = 9002
        private const val PROMPT_NOTIFICATION_ID = 9003

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ClipboardWatcherService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardWatcherService::class.java))
        }
    }
}
