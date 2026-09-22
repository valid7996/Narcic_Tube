package com.narcictub.app.domain.resolver

/**
 * Typed resolver failures (Phase 8). Messages are short and SAFE for display:
 * they never contain the URL, query strings, headers, response bodies, stack
 * traces or raw exception text. The ViewModel maps these to user-facing
 * wording and everything unknown to a generic message.
 */
sealed class MediaResolveException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /**
     * The source is a webpage (or a platform without an implemented
     * extractor). Honest unsupported result — nothing is fabricated.
     */
    class UnsupportedSource(
        message: String = "not a directly downloadable media file",
    ) : MediaResolveException(message)

    /**
     * PHASE 17: the URL was recognized as a known provider, but no
     * extractor for it is implemented yet. Honest — provider recognition
     * never fakes extraction support.
     */
    class UnsupportedProvider(val provider: com.narcictub.app.domain.model.MediaProvider) :
        MediaResolveException("provider extraction is not implemented")

    /**
     * PHASE 18: an extractor exists for the provider, but a legitimate,
     * policy-compliant extraction path is unavailable (e.g. the provider
     * requires authentication or circumvents of its access controls, which
     * NarcicTub does not do). Explicit and honest — never a fake result.
     */
    class ExtractionUnavailable(val provider: com.narcictub.app.domain.model.MediaProvider) :
        MediaResolveException("extraction is unavailable for this provider")

    /** The source server answered with an HTTP error status. */
    class Http(val statusCode: Int) :
        MediaResolveException("source server returned HTTP $statusCode")

    /** Connectivity failure: DNS, timeout, refused or aborted connection. */
    class Network(cause: Throwable) :
        MediaResolveException("source server could not be reached", cause)

    /** Network destination policy rejection (SSRF guard) on any hop. */
    class Policy(message: String) :
        MediaResolveException("destination blocked by security policy: $message")

    /** Unexpected internal failure — never a policy or server verdict. */
    class Internal(cause: Throwable) :
        MediaResolveException("resolution failed unexpectedly", cause)
}
