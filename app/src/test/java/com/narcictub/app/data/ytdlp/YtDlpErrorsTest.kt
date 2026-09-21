package com.narcictub.app.data.ytdlp

import com.narcictub.app.domain.resolver.MediaResolveException.ExtractionFailed.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class YtDlpErrorsTest {

    private fun reason(text: String) = YtDlpErrors.reasonOfText(text)

    @Test
    fun `login walls are classified as login required`() {
        assertEquals(Reason.LOGIN_REQUIRED, reason("ERROR: [youtube] x: Sign in to confirm you're not a bot"))
        assertEquals(Reason.LOGIN_REQUIRED, reason("ERROR: [Instagram] x: Instagram sent an empty media response. Check if this post is accessible in your browser without being logged-in. If it is not, then use --cookies-from-browser or --cookies"))
        assertEquals(Reason.LOGIN_REQUIRED, reason("ERROR: rate-limit reached or login required"))
    }

    @Test
    fun `format problems are not mistaken for unavailable media`() {
        assertEquals(Reason.NO_MEDIA, reason("ERROR: Requested format is not available. Use --list-formats"))
        assertEquals(Reason.NO_MEDIA, reason("ERROR: [Instagram] x: There is no video in this post"))
    }

    @Test
    fun `unavailable media`() {
        assertEquals(Reason.UNAVAILABLE, reason("ERROR: [youtube] x: Video unavailable"))
        assertEquals(Reason.UNAVAILABLE, reason("ERROR: [youtube] x: This video has been removed by the uploader"))
    }

    @Test
    fun `rate limits and network problems`() {
        assertEquals(Reason.RATE_LIMITED, reason("HTTP Error 429: Too Many Requests"))
        assertEquals(Reason.NETWORK, reason("ERROR: Unable to download webpage: <urlopen error timed out>"))
    }

    @Test
    fun `unknown text is OTHER and the engine failure is its own reason`() {
        assertEquals(Reason.OTHER, reason("something odd"))
        assertEquals(Reason.ENGINE_UNAVAILABLE, YtDlpErrors.reasonOf(YtDlpEngineException(RuntimeException("boom"))))
    }

    @Test
    fun `causes are inspected`() {
        val error = RuntimeException("wrapper", IllegalStateException("Sign in to confirm you're not a bot"))

        assertEquals(Reason.LOGIN_REQUIRED, YtDlpErrors.reasonOf(error))
    }

    @Test
    fun `download messages are fixed sentences without urls`() {
        Reason.entries.forEach { r ->
            val message = YtDlpErrors.downloadMessage(r)
            assertFalse(message.contains("http"))
            assertFalse(message.isBlank())
        }
    }
}
