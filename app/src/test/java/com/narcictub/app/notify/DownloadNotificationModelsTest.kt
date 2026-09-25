package com.narcictub.app.notify

import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 21 — pure notification-model tests: real-bytes-only progress,
 * indeterminate-when-unknown, completion only on actual COMPLETED, no
 * sensitive data in any snapshot field, throttling, and the restart
 * baseline rule. The controller applies these decisions verbatim.
 */
class DownloadNotificationModelsTest {

    private fun item(
        status: DownloadStatus,
        title: String = "clip.mp4",
    ) = HistoryItem(
        id = 7L,
        sourceUrl = "https://cdn.example.com/directory/clip.mp4?token=SECRET",
        title = title,
        status = status,
        createdAt = Instant.ofEpochMilli(1),
    )

    // ===== progress honesty =====

    @Test
    fun `progress percent comes from real bytes only`() {
        val snapshot = DownloadNotifications.build(
            7,
            item(DownloadStatus.DOWNLOADING),
            DownloadProgress(downloadedBytes = 700_000, totalBytes = 2_100_000),
        )!!
        assertEquals(33, snapshot.progressPercent) // floor(33.33)
    }

    @Test
    fun `unknown total renders indeterminate instead of an invented percent`() {
        val snapshot = DownloadNotifications.build(
            7,
            item(DownloadStatus.DOWNLOADING),
            DownloadProgress(downloadedBytes = 12_500_000, totalBytes = null),
        )!!
        assertNull(snapshot.progressPercent)
        assertTrue(snapshot.ongoing)
        assertTrue(snapshot.text.contains("12.5 MB"))
        assertFalse(snapshot.text.contains("%"))
    }

    @Test
    fun `progress never reports 100 before actual completion`() {
        // Bytes reached the declared total while still committing — the
        // notification must not claim completion.
        val snapshot = DownloadNotifications.build(
            7,
            item(DownloadStatus.DOWNLOADING),
            DownloadProgress(downloadedBytes = 2_100_000, totalBytes = 2_100_000),
        )!!
        assertEquals(99, snapshot.progressPercent)
    }

    @Test
    fun `percent is 100 only on the real COMPLETED transition`() {
        val snapshot = DownloadNotifications.build(7, item(DownloadStatus.COMPLETED), null)!!
        assertEquals(100, snapshot.progressPercent)
        assertFalse(snapshot.ongoing)
    }

    @Test
    fun `absurd or hostile progress values are clamped`() {
        val negative = DownloadNotifications.build(
            7, item(DownloadStatus.DOWNLOADING), DownloadProgress(-5, totalBytes = 100),
        )!!
        assertEquals(0, negative.progressPercent)
        val overflowing = DownloadNotifications.build(
            7, item(DownloadStatus.DOWNLOADING), DownloadProgress(Long.MAX_VALUE, totalBytes = 100),
        )!!
        assertEquals(99, overflowing.progressPercent)
    }

    // ===== status coverage =====

    @Test
    fun `every lifecycle state renders with ongoing only while active`() {
        val active = listOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED,
        )
        for (status in active) {
            val snapshot = DownloadNotifications.build(7, item(status), null)!!
            assertTrue("$status must be ongoing", snapshot.ongoing)
        }
        val terminal = listOf(
            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED,
        )
        for (status in terminal) {
            val snapshot = DownloadNotifications.build(7, item(status), null)!!
            assertFalse("$status must not be ongoing", snapshot.ongoing)
        }
    }

    @Test
    fun `terminal texts are the honest fixed phrases`() {
        assertEquals(
            "Download complete — clip.mp4",
            DownloadNotifications.build(7, item(DownloadStatus.COMPLETED), null)!!.text,
        )
        assertEquals(
            "Download failed — clip.mp4",
            DownloadNotifications.build(7, item(DownloadStatus.FAILED), null)!!.text,
        )
        assertEquals(
            "Download cancelled — clip.mp4",
            DownloadNotifications.build(7, item(DownloadStatus.CANCELLED), null)!!.text,
        )
    }

    // ===== privacy =====

    @Test
    fun `snapshots never contain url query data or secrets`() {
        val snapshot = DownloadNotifications.build(
            7,
            item(DownloadStatus.DOWNLOADING),
            DownloadProgress(700_000, totalBytes = 2_100_000),
        )!!
        val rendered = listOf(snapshot.title, snapshot.text).joinToString(" | ")
        assertFalse(rendered.contains("SECRET"))
        assertFalse(rendered.contains("token"))
        assertFalse(rendered.contains("https://"))
        assertFalse(rendered.contains("cdn.example.com"))
    }

    @Test
    fun `blank title falls back to the neutral download name`() {
        val snapshot = DownloadNotifications.build(7, item(DownloadStatus.COMPLETED, title = "  "), null)!!
        assertEquals("Download complete — Download", snapshot.text)
    }

    @Test
    fun `snapshot title is the fixed app name`() {
        val snapshot = DownloadNotifications.build(7, item(DownloadStatus.DOWNLOADING), null)!!
        assertEquals("NarcicTub", snapshot.title)
    }

    // ===== throttling =====

    private fun active(percent: Int, bytes: Long) =
        DownloadNotifications.build(
            7, item(DownloadStatus.DOWNLOADING), DownloadProgress(bytes, totalBytes = 100L),
        )!!.let { if (it.progressPercent == percent) it else it.copy(progressPercent = percent, text = it.text) }

    @Test
    fun `first sight always notifies`() {
        assertTrue(DownloadNotifications.shouldNotify(null, active(10, 10)))
    }

    @Test
    fun `same state never re-notifies`() {
        val first = active(10, 10)
        assertFalse(DownloadNotifications.shouldNotify(first, first))
    }

    @Test
    fun `small percent movement is coalesced - material movement notifies`() {
        val snapshot = DownloadNotifications.build(
            7, item(DownloadStatus.DOWNLOADING), DownloadProgress(700_000, totalBytes = 2_100_000),
        )!! // 33%
        val same = DownloadNotifications.build(
            7, item(DownloadStatus.DOWNLOADING), DownloadProgress(720_000, totalBytes = 2_100_000),
        )!! // 34% : same 5-point bucket
        val moved = DownloadNotifications.build(
            7, item(DownloadStatus.DOWNLOADING), DownloadProgress(1_100_000, totalBytes = 2_100_000),
        )!! // 52% : new bucket
        assertFalse(DownloadNotifications.shouldNotify(snapshot, same))
        assertTrue(DownloadNotifications.shouldNotify(snapshot, moved))
    }

    @Test
    fun `status change always notifies even in the same percent bucket`() {
        val downloading = active(10, 10)
        val paused = DownloadNotifications.build(7, item(DownloadStatus.PAUSED), null)!!
            .let { it.copy(progressPercent = 10) }
        assertTrue(DownloadNotifications.shouldNotify(downloading, paused))
    }

    @Test
    fun `indeterminate transfers throttle by real megabyte movement`() {
        val at11MB = DownloadNotifications.build(
            7, item(DownloadStatus.DOWNLOADING), DownloadProgress(12_100_000, totalBytes = null),
        )!! // 11.5 MB shown, bucket 11
        val sameBucket = DownloadNotifications.build(
            7, item(DownloadStatus.DOWNLOADING), DownloadProgress(12_400_000, totalBytes = null),
        )!! // 11.8 MB shown, still bucket 11
        val nextBucket = DownloadNotifications.build(
            7, item(DownloadStatus.DOWNLOADING), DownloadProgress(13_500_000, totalBytes = null),
        )!! // 12.8 MB shown, bucket 12
        assertFalse(DownloadNotifications.shouldNotify(at11MB, sameBucket))
        assertTrue(DownloadNotifications.shouldNotify(at11MB, nextBucket))
    }

    // ===== restart baseline rule =====

    @Test
    fun `unseen terminal rows from a previous session are skipped silently`() {
        for (status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)) {
            val snapshot = DownloadNotifications.build(7, item(status), null)!!
            assertEquals(
                "$status after restart must not re-notify",
                NotificationAction.SKIP,
                DownloadNotifications.decide(seenBefore = false, snapshot = snapshot),
            )
        }
    }

    @Test
    fun `active rows always post or update`() {
        for (status in listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)) {
            val snapshot = DownloadNotifications.build(7, item(status), null)!!
            assertEquals(
                NotificationAction.POST_OR_UPDATE,
                DownloadNotifications.decide(seenBefore = false, snapshot = snapshot),
            )
            assertEquals(
                NotificationAction.POST_OR_UPDATE,
                DownloadNotifications.decide(seenBefore = true, snapshot = snapshot),
            )
        }
    }

    @Test
    fun `terminal transitions observed live do notify`() {
        val snapshot = DownloadNotifications.build(7, item(DownloadStatus.COMPLETED), null)!!
        assertEquals(
            NotificationAction.POST_OR_UPDATE,
            DownloadNotifications.decide(seenBefore = true, snapshot = snapshot),
        )
    }

    // ===== foreground service stop condition =====

    @Test
    fun `queued downloading and paused rows keep the process alive`() {
        for (status in listOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED,
        )) {
            assertTrue("$status must count as in flight", DownloadNotifications.isInFlight(status))
        }
    }

    @Test
    fun `terminal rows release the foreground service`() {
        for (status in listOf(
            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED,
        )) {
            assertFalse("$status must not count as in flight", DownloadNotifications.isInFlight(status))
        }
    }
}
