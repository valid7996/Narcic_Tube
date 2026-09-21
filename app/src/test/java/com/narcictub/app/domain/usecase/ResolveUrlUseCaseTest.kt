package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.resolver.MediaResolveException
import com.narcictub.app.domain.resolver.MediaResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveUrlUseCaseTest {

    private class RecordingResolver : MediaResolver {
        val received = mutableListOf<String>()
        var nextResult: Result<MediaInfo> =
            Result.failure(MediaResolveException.UnsupportedSource())

        override suspend fun resolve(url: String): Result<MediaInfo> {
            received.add(url)
            return nextResult
        }
    }

    @Test
    fun `invalid url never reaches the resolver`() = runTest {
        val resolver = RecordingResolver()
        val useCase = ResolveUrlUseCase(resolver)

        val result = useCase("not a url")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlException)
        assertEquals(0, resolver.received.size)
    }

    @Test
    fun `url with embedded credentials never reaches the resolver`() = runTest {
        val resolver = RecordingResolver()
        val useCase = ResolveUrlUseCase(resolver)

        val result = useCase("https://user:pass@example.com/video")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlException)
        assertEquals(0, resolver.received.size)
    }

    @Test
    fun `valid url is normalized then forwarded`() = runTest {
        val resolver = RecordingResolver()
        val useCase = ResolveUrlUseCase(resolver)

        useCase("  https://example.com/watch?v=1  ")

        assertEquals(listOf("https://example.com/watch?v=1"), resolver.received)
    }

    @Test
    fun `typed resolver failure propagates as-is`() = runTest {
        val resolver = RecordingResolver()
        resolver.nextResult = Result.failure(MediaResolveException.UnsupportedSource())
        val useCase = ResolveUrlUseCase(resolver)

        val result = useCase("https://example.com/watch?v=1")

        assertTrue(result.exceptionOrNull() is MediaResolveException.UnsupportedSource)
    }

    @Test
    fun `successful resolution passes metadata through unchanged`() = runTest {
        val info = MediaInfo(
            sourceUrl = "https://example.com/clip.mp4",
            title = "clip.mp4",
            host = "example.com",
            mimeType = "video/mp4",
            sizeBytes = 1024L,
            isDirectFile = true,
            downloadUrl = "https://example.com/clip.mp4",
        )
        val resolver = RecordingResolver()
        resolver.nextResult = Result.success(info)
        val useCase = ResolveUrlUseCase(resolver)

        val result = useCase("https://example.com/clip.mp4")

        assertEquals(info, result.getOrNull())
    }
}
