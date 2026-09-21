package com.narcictub.app.ui.home

import com.narcictub.app.domain.share.SharedTextUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 17 — share intake state: raw shared text becomes a ONE-SHOT,
 * validated event; consumption clears it so lifecycle recreation and
 * repeated compositions cannot re-process the same share.
 */
class ShareIntakeViewModelTest {

    private fun viewModel() = ShareIntakeViewModel()

    @Test
    fun `plain shared url becomes a url event`() {
        val vm = viewModel()
        vm.onNewSharedText("https://example.com/clip.mp4")

        assertEquals(PendingShare.Url("https://example.com/clip.mp4"), vm.pending.value)
    }

    @Test
    fun `whitespace around the shared url is handled`() {
        val vm = viewModel()
        vm.onNewSharedText("  https://example.com/clip.mp4 \n")

        assertEquals(PendingShare.Url("https://example.com/clip.mp4"), vm.pending.value)
    }

    @Test
    fun `text without a usable link becomes a safe invalid event`() {
        val vm = viewModel()
        vm.onNewSharedText("look at this amazing cat")

        assertEquals(
            PendingShare.Invalid("The shared text doesn't contain a supported link."),
            vm.pending.value,
        )
    }

    @Test
    fun `ambiguous shared text names the link count`() {
        val vm = viewModel()
        vm.onNewSharedText("https://example.com/a.mp4 or https://example.com/b.mp4?")

        val pending = vm.pending.value
        assertTrue(pending is PendingShare.Invalid)
        assertTrue(
            (pending as PendingShare.Invalid).message.contains("2 different links"),
        )
    }

    @Test
    fun `null shared text is handled as invalid`() {
        val vm = viewModel()
        vm.onNewSharedText(null)

        assertTrue(vm.pending.value is PendingShare.Invalid)
    }

    @Test
    fun `consumption clears the event exactly once`() {
        val vm = viewModel()
        vm.onNewSharedText("https://example.com/clip.mp4")
        vm.onConsumed()

        assertNull("a consumed share must never re-fire", vm.pending.value)

        // A NEW share intent is a new user action and works again.
        vm.onNewSharedText("https://example.com/other.mp4")
        assertEquals(PendingShare.Url("https://example.com/other.mp4"), vm.pending.value)
    }

    @Test
    fun `security-sensitive inputs are rejected as invalid`() {
        // Credential-bearing and non-http shares must never produce a Url event.
        for (text in listOf(
            "https://user:pass@example.com/private.mp4",
            "ftp://example.com/file.mp4",
            "file:///etc/passwd",
            "javascript:alert(1)",
        )) {
            val vm = viewModel()
            vm.onNewSharedText(text)
            assertTrue(
                "expected Invalid for '$text'",
                vm.pending.value is PendingShare.Invalid,
            )
        }
    }
}
