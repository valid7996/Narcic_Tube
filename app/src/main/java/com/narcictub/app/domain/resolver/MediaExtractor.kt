package com.narcictub.app.domain.resolver

import com.narcictub.app.domain.model.MediaInfo

/**
 * PHASE 17/18: contract for a provider-specific media extractor. Implementations
 * register in the extractor registry (data layer); the registry picks the
 * FIRST supporting extractor by DESCENDING [priority] — deterministic, never
 * registration-order dependent.
 *
 * Three separate concerns, deliberately:
 *  - [supports] — static, cheap URL/provider matching. Recognizing a
 *    provider does NOT mean the media is extractable.
 *  - [extract] — the actual (potentially expensive) resolution, which must
 *    still honor every security policy and never fabricate metadata.
 *  - [priority] — deterministic selection when several extractors could
 *    claim the same URL; higher wins.
 *
 * SECURITY CONTRACT (Phase 18): every implementation MUST independently
 * validate the URL it is given (scheme, host, userinfo, encoded-host
 * tricks such as youtube.com.evil.com) and MUST route all network traffic
 * through the existing policy-gated networking abstractions
 * (NetworkDestinationPolicy + bounded reads). Detection output is never
 * trusted as a security boundary.
 */
interface MediaExtractor {

    /** Deterministic selection weight; higher priority wins. */
    val priority: Int

    /** Whether this extractor claims [url] for extraction. */
    fun supports(url: String): Boolean

    /** Resolves [url] into [MediaInfo]. Failures come back as Result failures. */
    suspend fun extract(url: String): Result<MediaInfo>
}
