package com.narcictub.app.data.ytdlp
import com.narcictub.app.data.resolver.InstagramPhotoResolver
import io.mockk.mockk

import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.resolver.MediaResolveException
import com.narcictub.app.domain.resolver.MediaResolveException.ExtractionFailed.Reason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpExtractorTest {

    private val engine = mockk<YtDlpEngine>()
    private val extractor = YtDlpExtractor(engine, InstagramPhotoResolver(mockk(relaxed = true)))

    @Test
    fun `claims only real youtube and instagram urls`() {
        assertTrue(extractor.supports("https://www.youtube.com/watch?v=1"))
        assertTrue(extractor.supports("https://youtu.be/1"))
        assertTrue(extractor.supports("https://www.instagram.com/p/x/"))
        assertFalse(extractor.supports("https://cdn.example.com/clip.mp4"))
        assertFalse(extractor.supports("https://youtube.com.evil.com/watch?v=1"))
        assertFalse(extractor.supports("https://127.0.0.1/"))
    }

    @Test
    fun `outranks the direct file fallback`() {
        assertTrue(extractor.priority > 0)
    }

    @Test
    fun `extract returns the parsed media`() = runTest {
        coEvery { engine.dumpJson(any()) } returns YtDlpSamples.YOUTUBE

        val info = extractor.extract("https://www.youtube.com/watch?v=abc123").getOrThrow()

        assertEquals("Sample video", info.title)
        assertEquals(MediaProvider.YOUTUBE, info.provider)
        assertEquals(5, info.variants.size)
    }

    @Test
    fun `a pasted quality fragment never reaches yt-dlp`() = runTest {
        coEvery { engine.dumpJson(any()) } returns YtDlpSamples.YOUTUBE

        extractor.extract("https://www.youtube.com/watch?v=abc123#nt-f=--exec").getOrThrow()

        coVerify { engine.dumpJson("https://www.youtube.com/watch?v=abc123") }
    }

    @Test
    fun `yt-dlp stderr is classified never surfaced`() = runTest {
        coEvery { engine.dumpJson(any()) } throws RuntimeException("ERROR: Sign in to confirm you're not a bot https://secret.example/")

        val error = extractor.extract("https://www.youtube.com/watch?v=abc123").exceptionOrNull()

        assertTrue(error is MediaResolveException.ExtractionFailed)
        error as MediaResolveException.ExtractionFailed
        assertEquals(Reason.LOGIN_REQUIRED, error.reason)
        assertEquals(MediaProvider.YOUTUBE, error.provider)
        assertFalse(error.message.orEmpty().contains("secret"))
    }

    @Test
    fun `engine startup failure maps to ENGINE_UNAVAILABLE`() = runTest {
        coEvery { engine.dumpJson(any()) } throws YtDlpEngineException(RuntimeException("no python"))

        val error = extractor.extract("https://www.instagram.com/p/x/").exceptionOrNull()

        assertEquals(Reason.ENGINE_UNAVAILABLE, (error as MediaResolveException.ExtractionFailed).reason)
    }

    @Test
    fun `other hosts are refused without touching the engine`() = runTest {
        val error = extractor.extract("https://cdn.example.com/clip.mp4").exceptionOrNull()

        assertTrue(error is MediaResolveException.UnsupportedSource)
        coVerify(exactly = 0) { engine.dumpJson(any()) }
    }
}
