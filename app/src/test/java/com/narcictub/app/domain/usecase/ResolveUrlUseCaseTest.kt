package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.resolver.MediaResolver
import com.narcictub.app.domain.resolver.ResolverNotImplementedException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveUrlUseCaseTest {

    private class RecordingResolver : MediaResolver {
        val received = mutableListOf<String>()
        override suspend fun resolve(url: String): Result<MediaInfo> {
            received.add(url)
            return Result.failure(ResolverNotImplementedException())
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
    fun `resolver failure propagates as-is`() = runTest {
        val useCase = ResolveUrlUseCase(RecordingResolver())

        val result = useCase("https://example.com/watch?v=1")

        assertTrue(result.exceptionOrNull() is ResolverNotImplementedException)
    }
}
