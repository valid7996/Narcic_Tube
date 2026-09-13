package com.narcictub.app.ui.home

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty and invalid`() {
        val vm = HomeViewModel()
        val state = vm.uiState.value
        assertEquals("", state.url)
        assertFalse(state.isUrlValid)
        assertNull(state.resolvedHost)
        assertFalse(state.isResolving)
    }

    @Test
    fun `valid url marks state valid`() = runTest {
        val vm = HomeViewModel()
        vm.onUrlChange("https://example.com/watch?v=1")
        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.isUrlValid)
            assertNull(state.validationMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invalid url sets validation message`() {
        val vm = HomeViewModel()
        vm.onUrlChange("ftp://example.com/x")
        val state = vm.uiState.value
        assertFalse(state.isUrlValid)
        assertEquals("Link must start with http:// or https://", state.validationMessage)
    }

    @Test
    fun `typing clears previous resolve result`() = runTest {
        val vm = HomeViewModel()
        vm.onUrlChange("https://example.com/a")
        vm.onResolve()
        advanceUntilIdle()
        assertEquals("example.com", vm.uiState.value.resolvedHost)

        vm.onUrlChange("https://example.com/b")
        assertNull(vm.uiState.value.resolvedHost)
    }

    @Test
    fun `resolve with invalid url does not start resolving`() = runTest {
        val vm = HomeViewModel()
        vm.onUrlChange("not a url")
        vm.onResolve()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertFalse(state.isResolving)
        assertNull(state.resolvedHost)
    }

    @Test
    fun `resolve with valid url exposes host`() = runTest {
        val vm = HomeViewModel()
        vm.onUrlChange("https://example.com/watch?v=1")
        vm.onResolve()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertFalse(state.isResolving)
        assertEquals("example.com", state.resolvedHost)
    }

    @Test
    fun `clear resets to initial`() = runTest {
        val vm = HomeViewModel()
        vm.onUrlChange("https://example.com/a")
        vm.onResolve()
        advanceUntilIdle()
        vm.onClear()
        assertEquals(HomeUiState(), vm.uiState.value)
    }
}
