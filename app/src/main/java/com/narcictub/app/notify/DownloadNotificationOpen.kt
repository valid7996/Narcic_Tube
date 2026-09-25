package com.narcictub.app.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.narcictub.app.MainActivity

/** Open intents for notifications — explicit launcher target, immutable. */
internal object DownloadNotificationOpen {

    /** Opens the main app (Downloads land via normal launch behavior). */
    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE,
    )
}
