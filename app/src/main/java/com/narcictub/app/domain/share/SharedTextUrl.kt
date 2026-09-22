package com.narcictub.app.domain.share

import com.narcictub.app.domain.UrlValidator

/**
 * PHASE 17: conservative extraction of a single usable HTTP/HTTPS URL from
 * text received through Android Share. Pure and deterministic — no network,
 * no interpretation of arbitrary text as a link.
 *
 * Policy (pinned by tests):
 *  - the complete trimmed text may itself be the URL (fast path);
 *  - otherwise only explicit http/https candidates inside the text are
 *    considered, with common trailing punctuation stripped;
 *  - every candidate must pass the existing URL validation;
 *  - zero valid URLs → [Extraction.None];
 *  - more than one DISTINCT valid URL → [Extraction.Ambiguous] (never a
 *    silent pick).
 */
object SharedTextUrl {

    sealed interface Extraction {
        /** Exactly one usable link. */
        data class Single(val url: String) : Extraction

        /** No usable link in the shared text. */
        data object None : Extraction

        /** Multiple distinct valid links — the user must pick one explicitly. */
        data class Ambiguous(val candidates: Int) : Extraction
    }

    private val CANDIDATE = Regex("""https?://[^\s"'<>]+""")
    private val TRAILING_PUNCTUATION = Regex("""[.,;:!?)\]}'"]+$""")

    fun extract(raw: String?): Extraction {
        if (raw.isNullOrBlank()) return Extraction.None

        // Fast path: the whole (trimmed) text is the link itself — this also
        // preserves URLs the candidate regex would shorten.
        val full = raw.trim()
        if (UrlValidator.isValidHttpUrl(full)) return Extraction.Single(full)

        val distinctValid = CANDIDATE.findAll(full)
            .map { it.value.replace(TRAILING_PUNCTUATION, "") }
            .filter { UrlValidator.isValidHttpUrl(it) }
            .distinct()
            .toList()

        return when (distinctValid.size) {
            1 -> Extraction.Single(distinctValid.single())
            0 -> Extraction.None
            else -> Extraction.Ambiguous(distinctValid.size)
        }
    }
}
