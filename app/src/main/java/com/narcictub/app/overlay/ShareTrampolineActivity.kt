package com.narcictub.app.overlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.narcictub.app.MainActivity

/**
 * The real target of Android's Share sheet for NarcicTub (see the manifest —
 * MainActivity no longer declares the SEND filter). This activity is
 * invisible (Theme.NarcicTub.Transparent) and finishes itself immediately:
 * its only job is deciding where the shared link goes, without ever
 * bringing the app's UI to the foreground and interrupting whatever the
 * user was doing (e.g. watching a YouTube video).
 *
 *  - Overlay permission granted → the floating bubble opens over the
 *    current app ([OverlayBubbleService]); NarcicTub itself never appears.
 *  - Not granted → falls back to the exact previous behavior: MainActivity
 *    opens with the link pre-filled and resolving.
 */
class ShareTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
        val url = BubbleUrlIntake.singleUrlOrNull(text)

        when {
            url != null && OverlayPermission.isGranted(this) -> {
                OverlayBubbleService.showForUrl(this, url)
            }
            url != null -> {
                // No overlay permission: behave exactly as before this
                // feature existed — open the app with the link pre-filled.
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        action = MainActivity.ACTION_OPEN_URL
                        putExtra(MainActivity.EXTRA_URL, url)
                    },
                )
            }
            else -> Toast.makeText(this, "That shared text doesn't contain a supported link.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
