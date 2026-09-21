package com.narcictub.app.data.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class YtDlpProgressTest {

    @Test
    fun `percent and size are combined into real byte progress`() {
        val p = YtDlpProgress.from(50f, "[download]  50.0% of  100.00MiB at  2.10MiB/s ETA 00:24")

        assertNotNull(p)
        assertEquals(100L * 1024 * 1024, p!!.totalBytes)
        assertEquals(50L * 1024 * 1024, p.downloadedBytes)
    }

    @Test
    fun `approximate sizes with a tilde are understood`() {
        val p = YtDlpProgress.from(10f, "[download]  10.0% of ~  20.00KiB at  1.00KiB/s ETA 00:10")

        assertEquals(20L * 1024, p!!.totalBytes)
    }

    @Test
    fun `missing size stays indeterminate instead of inventing a total`() {
        val p = YtDlpProgress.from(42f, "[download]  42.0% at  1.00MiB/s")

        assertNull(p!!.totalBytes)
        assertEquals(0L, p.downloadedBytes)
    }

    @Test
    fun `non progress values are ignored`() {
        assertNull(YtDlpProgress.from(-1f, "[info] something"))
        assertNull(YtDlpProgress.from(Float.NaN, "[download] x"))
    }

    @Test
    fun `percent is clamped`() {
        val p = YtDlpProgress.from(250f, "[download] 100.0% of 1.00MiB")

        assertEquals(p!!.totalBytes, p.downloadedBytes)
    }
}
