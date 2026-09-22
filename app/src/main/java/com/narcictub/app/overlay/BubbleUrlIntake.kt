package com.narcictub.app.overlay

import com.narcictub.app.domain.share.SharedTextUrl

/**
 * The single, shared decision of "is this text a link worth offering the
 * floating bubble for", reused by the Share trampoline and the clipboard
 * watcher. Deliberately the same conservative rule as in-app Share intake
 * ([SharedTextUrl]) — no separate, looser heuristic for the background path.
 */
object BubbleUrlIntake {
    fun singleUrlOrNull(raw: String?): String? =
        (SharedTextUrl.extract(raw) as? SharedTextUrl.Extraction.Single)?.url
}
