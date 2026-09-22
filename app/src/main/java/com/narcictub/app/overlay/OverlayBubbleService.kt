package com.narcictub.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.narcictub.app.MainActivity
import com.narcictub.app.domain.NetworkDestinationPolicy
import com.narcictub.app.domain.UrlValidator
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.model.MediaVariant
import com.narcictub.app.domain.resolver.MediaResolveException
import com.narcictub.app.domain.usecase.EnqueueDownloadUseCase
import com.narcictub.app.domain.usecase.ResolveUrlUseCase
import com.narcictub.app.ui.home.sortedVariantsForDisplay
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A floating "chat head" for one link at a time — the whole point being that
 * the user never leaves whatever app they were in. Started either by
 * [ShareTrampolineActivity] (Share button from YouTube/Instagram) or by
 * [ClipboardWatcherService] (a supported link copied to the clipboard).
 *
 * The bubble only RESOLVES and ENQUEUES; the actual bytes still flow through
 * the app's existing download pipeline and its own notification
 * ([com.narcictub.app.notify.DownloadNotificationController]), so the bubble
 * can safely disappear a couple of seconds after Download is tapped.
 *
 * One bubble at a time: a new start command cancels whatever the current one
 * was doing and starts over with the new URL (same "latest request wins"
 * rule as sharing a new link into the open app).
 */
@AndroidEntryPoint
class OverlayBubbleService : Service() {

    @Inject lateinit var resolveUrl: ResolveUrlUseCase
    @Inject lateinit var enqueueDownload: EnqueueDownloadUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolveJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var bubble: BubbleViews.Bubble? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: BubbleViews.Panel? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var pendingUrl: String? = null
    private var resolvedMedia: MediaInfo? = null
    private var selectedVariantUrl: String? = null
    private var resolveError: String? = null
    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        if (url.isNullOrBlank()) {
            if (bubble == null) stopSelf()
            return START_NOT_STICKY
        }
        ensureBubbleShown()
        startResolving(url)
        return START_NOT_STICKY
    }

    // ===== window management =====

    private fun ensureBubbleShown() {
        if (bubble != null) return
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val b = BubbleViews.Bubble(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - dp(72)
            y = metrics.heightPixels / 3
        }
        b.root.setOnTouchListener(DragToClickListener(
            onClick = { toggleExpanded() },
            onMoved = { dx, dy ->
                params.x += dx
                params.y += dy
                runCatching { windowManager.updateViewLayout(b.root, params) }
            },
        ))
        windowManager.addView(b.root, params)
        bubble = b
        bubbleParams = params
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun toggleExpanded() {
        expanded = !expanded
        if (expanded) showPanel() else hidePanel()
    }

    private fun showPanel() {
        val bubbleP = bubbleParams ?: return
        if (panel != null) return
        val p = BubbleViews.Panel(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleP.x - dp(200)).coerceAtLeast(dp(8))
            y = bubbleP.y
        }
        p.closeButton.setOnClickListener { dismiss() }
        p.openInAppButton.setOnClickListener { openInApp() }
        p.downloadButton.setOnClickListener { onDownloadTapped() }
        windowManager.addView(p.root, params)
        panel = p
        panelParams = params
        renderPanel()
    }

    private fun hidePanel() {
        val p = panel ?: return
        runCatching { windowManager.removeView(p.root) }
        panel = null
        panelParams = null
    }

    // ===== resolve + download =====

    private fun startResolving(url: String) {
        resolveJob?.cancel()
        pendingUrl = url
        resolvedMedia = null
        selectedVariantUrl = null
        resolveError = null
        expanded = false
        hidePanel()
        bubble?.showBusy(true)
        bubble?.showState(Color.parseColor("#2962FF"))

        resolveJob = scope.launch {
            val result = resolveUrl(url)
            bubble?.showBusy(false)
            result.fold(
                onSuccess = { info ->
                    resolvedMedia = info
                    val variants = sortedVariantsForDisplay(info.variants)
                    selectedVariantUrl = variants.firstOrNull { isDownloadable(it) }?.downloadUrl
                    bubble?.showState(Color.parseColor("#00C853"))
                    // Ready: expand automatically ONCE so the user sees it
                    // arrived, without needing to hunt for the bubble.
                    toggleExpanded()
                },
                onFailure = { error ->
                    resolveError = messageFor(error)
                    bubble?.showState(Color.parseColor("#D50000"))
                    toggleExpanded()
                },
            )
        }
    }

    private fun renderPanel() {
        val p = panel ?: return
        val info = resolvedMedia
        when {
            info != null -> {
                p.titleView.text = info.title ?: info.host
                val variants = sortedVariantsForDisplay(info.variants)
                p.statusView.text = if (variants.isEmpty()) "No downloadable media was found." else "Pick a quality:"
                p.renderVariants(variants, selectedVariantUrl) { variant ->
                    selectedVariantUrl = variant.downloadUrl
                    renderPanel()
                }
                p.downloadButton.isEnabled = selectedVariantUrl != null
            }
            resolveError != null -> {
                p.titleView.text = "Couldn't read this link"
                p.statusView.text = resolveError
                p.renderVariants(emptyList(), null) {}
                p.downloadButton.isEnabled = false
            }
            else -> {
                p.titleView.text = "Resolving…"
                p.statusView.text = ""
                p.renderVariants(emptyList(), null) {}
                p.downloadButton.isEnabled = false
            }
        }
    }

    private fun isDownloadable(variant: MediaVariant): Boolean =
        UrlValidator.isValidHttpUrl(variant.downloadUrl) && NetworkDestinationPolicy.isAllowed(variant.downloadUrl)

    private fun onDownloadTapped() {
        val info = resolvedMedia ?: return
        val variant = info.variants.firstOrNull { it.downloadUrl == selectedVariantUrl } ?: return
        if (!isDownloadable(variant)) return
        panel?.downloadButton?.isEnabled = false
        panel?.statusView?.text = "Queued — see the download notification."
        val title = info.title?.takeIf { info.provider != MediaProvider.UNKNOWN }
        scope.launch {
            enqueueDownload(variant.downloadUrl, variant.durationSeconds, title)
            // The system download notification takes over from here; the
            // bubble's only remaining job is to get out of the way.
            kotlinx.coroutines.delay(1200)
            dismiss()
        }
    }

    private fun openInApp() {
        val url = pendingUrl ?: return
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            action = MainActivity.ACTION_OPEN_URL
            putExtra(MainActivity.EXTRA_URL, url)
        }
        startActivity(intent)
        dismiss()
    }

    private fun dismiss() {
        resolveJob?.cancel()
        hidePanel()
        bubble?.let { runCatching { windowManager.removeView(it.root) } }
        bubble = null
        bubbleParams = null
        stopSelf()
    }

    override fun onDestroy() {
        hidePanel()
        bubble?.let { runCatching { windowManager.removeView(it.root) } }
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Safe error text — same rule as the in-app ViewModel: no URLs, no raw
     * exception text.
     */
    private fun messageFor(error: Throwable): String = when (error) {
        is MediaResolveException.ExtractionFailed -> when (error.reason) {
            MediaResolveException.ExtractionFailed.Reason.LOGIN_REQUIRED ->
                "Needs a login — import cookies.txt in the app's Settings."
            MediaResolveException.ExtractionFailed.Reason.UNAVAILABLE -> "Private, removed, or blocked."
            MediaResolveException.ExtractionFailed.Reason.NO_MEDIA -> "No downloadable video was found."
            MediaResolveException.ExtractionFailed.Reason.RATE_LIMITED -> "Rate limited — try again later."
            MediaResolveException.ExtractionFailed.Reason.NETWORK -> "Network error."
            MediaResolveException.ExtractionFailed.Reason.ENGINE_UNAVAILABLE -> "Download engine couldn't start."
            MediaResolveException.ExtractionFailed.Reason.OTHER -> "The site may have changed."
        }
        is MediaResolveException.Http -> "Server error (HTTP ${error.statusCode})."
        is MediaResolveException.Network -> "Couldn't reach the server."
        is MediaResolveException.Policy -> "This destination is blocked."
        is MediaResolveException.UnsupportedSource -> "Not a supported link."
        else -> "Couldn't resolve that link."
    }

    private fun buildForegroundNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Floating download bubble", NotificationManager.IMPORTANCE_MIN)
            channel.setShowBadge(false)
            manager?.createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Floating download bubble is active")
            .setContentText("Tap to open NarcicTub")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .build()
    }

    companion object {
        const val EXTRA_URL = "com.narcictub.app.overlay.EXTRA_URL"
        private const val CHANNEL_ID = "overlay_bubble"
        private const val NOTIFICATION_ID = 9001

        /** Starts (or replaces the URL of) the floating bubble for [url]. */
        fun showForUrl(context: Context, url: String) {
            val intent = Intent(context, OverlayBubbleService::class.java).putExtra(EXTRA_URL, url)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/**
 * Combined drag + tap handling for an overlay view: movement past
 * [SLOP_PX] is treated as a drag (reported incrementally via [onMoved]);
 * anything smaller is a tap ([onClick]) on ACTION_UP. Standard "chat head"
 * gesture handling — WindowManager views don't get normal click/drag
 * gesture support for free.
 */
private class DragToClickListener(
    private val onClick: () -> Unit,
    private val onMoved: (dx: Int, dy: Int) -> Unit,
) : View.OnTouchListener {

    private var lastX = 0f
    private var lastY = 0f
    private var totalMovement = 0f

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                totalMovement = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastX).toInt()
                val dy = (event.rawY - lastY).toInt()
                if (dx != 0 || dy != 0) {
                    onMoved(dx, dy)
                    totalMovement += abs(dx) + abs(dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (totalMovement < SLOP_PX) onClick()
            }
        }
        return true
    }

    private companion object {
        const val SLOP_PX = 20f
    }
}
