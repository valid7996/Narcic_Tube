package com.narcictub.app.data.resolver

import com.narcictub.app.domain.resolver.MediaResolver
import com.narcictub.app.domain.resolver.ResolverNotImplementedException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the stub resolver. The whole point: until a real
 * extractor exists, resolve() must FAIL EXPLICITLY — never fake metadata.
 */
class StubMediaResolverTest {

    private val resolver: MediaResolver = StubMediaResolver()

    @Test
    fun `resolve fails with ResolverNotImplementedException`() = runTest {
        val result = resolver.resolve("https://example.com/watch?v=1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ResolverNotImplementedException)
    }

    @Test
    fun `resolve never returns fabricated MediaInfo`() = runTest {
        val result = resolver.resolve("https://example.com/watch?v=1")
        assertTrue(result.getOrNull() == null)
    }

    @Test
    fun `failure message is explicit about not extracting anything`() = runTest {
        val message = resolver.resolve("https://example.com/x")
            .exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("not implemented", ignoreCase = true))
    }
}
