package com.narcictub.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Smoke test: verifies the test infrastructure is wired correctly
 * (unit tests run, coroutines-test works on JVM).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SanityTest {

    @Test
    fun `sanity assertion passes`() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun `runTest works with suspending block`() = runTest {
        val result = suspendFunction()
        assertNotNull(result)
    }

    private suspend fun suspendFunction(): String = "ok"
}
