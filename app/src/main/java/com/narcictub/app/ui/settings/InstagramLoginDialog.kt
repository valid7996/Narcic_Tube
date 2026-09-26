package com.narcictub.app.ui.settings

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.narcictub.app.data.ytdlp.YtDlpCookies
import com.narcictub.app.ui.theme.honeySuccessColor
import kotlinx.coroutines.delay

/**
 * HONEY — in-app Instagram login: a WebView where the user logs into their
 * OWN account; the session cookies are captured into the same cookies file
 * yt-dlp and the Instagram photo resolver read. No manual cookies.txt
 * export needed. The session never leaves the device and is never logged.
 */
@Composable
fun InstagramLoginDialog(
    onSessionSaved: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var savedOk by remember { mutableStateOf<Boolean?>(null) }
    var done by remember { mutableStateOf(false) }

    // بررسی دوره‌ای: هر وقت sessionid در کوکی‌های اینستاگرام ظاهر شد = لاگین موفق
    LaunchedEffect(Unit) {
        while (!done) {
            delay(700)
            val cookie = CookieManager.getInstance().getCookie("https://www.instagram.com")
            if (cookie != null && cookie.contains("sessionid=")) {
                val ok = YtDlpCookies.saveInstagramSession(context, cookie)
                CookieManager.getInstance().setCookie(
                    "https://www.instagram.com",
                    "sessionid=; Max-Age=0; path=/",
                )
                CookieManager.getInstance().flush()
                savedOk = ok
                done = true
                delay(900)
                onSessionSaved(ok)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 32.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Log in to Instagram",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Your session stays only on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                AndroidWebView()
                if (done) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when (savedOk) {
                                true -> "Logged in — session saved ✓"
                                else -> "Couldn't save the session."
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (savedOk == true) honeySuccessColor() else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Text(
                text = "Log in with your own account. After login, Instagram " +
                    "photos and videos (including ones you follow) can be downloaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            )
        }
    }
}

@Composable
private fun AndroidWebView() {
    val context = LocalContext.current
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = WebViewClient()
                loadUrl("https://www.instagram.com/accounts/login/")
            }
        },
    )
}
