package com.narcictub.app.data.ytdlp

import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.resolver.MediaResolveException
import com.narcictub.app.domain.resolver.MediaResolveException.ExtractionFailed.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class YtDlpInfoParserTest {

    private val page = "https://www.youtube.com/watch?v=abc123"

    private fun parse(json: String, provider: MediaProvider = MediaProvider.YOUTUBE, url: String = page) =
        YtDlpInfoParser.parse(url, provider, json)

    private fun specOf(variantUrl: String): String? = YtDlpUrl.parse(variantUrl).formatSpec

    @Test
    fun `reads real title duration thumbnail and host`() {
        val info = parse(YtDlpSamples.YOUTUBE)

        assertEquals("Sample video", info.title)
        assertEquals(212L, info.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/abc123/hq.jpg", info.thumbnailUrl)
        assertEquals("www.youtube.com", info.host)
        assertEquals(MediaProvider.YOUTUBE, info.provider)
        assertFalse(info.isDirectFile)
        assertEquals(page, info.sourceUrl)
    }

    @Test
    fun `offers one variant per resolution plus audio only, best first`() {
        val variants = parse(YtDlpSamples.YOUTUBE).variants

        assertEquals(listOf<Int?>(1440, 1080, 720, 360, null), variants.map { it.height })
    }

    @Test
    fun `h264 video is preferred and paired with m4a audio for an mp4 result`() {
        val v1080 = parse(YtDlpSamples.YOUTUBE).variants.first { it.height == 1080 }

        assertEquals("137+140", specOf(v1080.downloadUrl))
        assertEquals("mp4", v1080.container)
        assertEquals("video/mp4", v1080.mimeType)
        assertEquals("1080p", v1080.qualityLabel)
        assertEquals(1920, v1080.width)
        assertEquals(53_400_000L, v1080.sizeBytes)
    }

    @Test
    fun `vp9-only heights pair with webm audio into a webm result`() {
        val v1440 = parse(YtDlpSamples.YOUTUBE).variants.first { it.height == 1440 }

        assertEquals("271+251", specOf(v1440.downloadUrl))
        assertEquals("webm", v1440.container)
        assertEquals(93_500_000L, v1440.sizeBytes)
    }

    @Test
    fun `a muxed format wins its height and needs no merge`() {
        val v360 = parse(YtDlpSamples.YOUTUBE).variants.first { it.height == 360 }

        assertEquals("18", specOf(v360.downloadUrl))
        assertEquals(9_000_000L, v360.sizeBytes)
        assertEquals("360p", v360.qualityLabel)
    }

    @Test
    fun `audio only variant prefers m4a and carries no video metadata`() {
        val audio = parse(YtDlpSamples.YOUTUBE).variants.last()

        assertEquals("140", specOf(audio.downloadUrl))
        assertEquals("audio/mp4", audio.mimeType)
        assertEquals("m4a", audio.container)
        assertNull(audio.height)
        assertNull(audio.width)
        assertNull(audio.qualityLabel)
        assertEquals(3_400_000L, audio.sizeBytes)
    }

    @Test
    fun `storyboards and hls twins are ignored while real formats exist`() {
        val specs = parse(YtDlpSamples.YOUTUBE).variants.mapNotNull { specOf(it.downloadUrl) }

        assertTrue(specs.none { it.contains("sb0") || it.contains("hls") })
    }

    @Test
    fun `hls is used when it is all there is`() {
        val variants = parse(YtDlpSamples.HLS_ONLY).variants

        assertEquals(listOf<Int?>(720, 360), variants.map { it.height })
        assertEquals(listOf<String?>("hls-720", "hls-360"), variants.map { specOf(it.downloadUrl) })
    }

    @Test
    fun `variant urls are unique and keep the page url`() {
        val variants = parse(YtDlpSamples.YOUTUBE).variants

        assertEquals(variants.size, variants.map { it.downloadUrl }.toSet().size)
        assertTrue(variants.all { YtDlpUrl.parse(it.downloadUrl).pageUrl == page })
    }

    @Test
    fun `a single plain url without formats becomes one best variant`() {
        val info = parse(YtDlpSamples.SINGLE_URL, MediaProvider.INSTAGRAM, "https://www.instagram.com/p/x/")

        assertEquals(1, info.variants.size)
        assertEquals("best", specOf(info.variants.single().downloadUrl))
        assertEquals(1280, info.variants.single().height)
        assertEquals(15L, info.durationSeconds)
    }

    @Test
    fun `carousel playlists use the first entry and the playlist title`() {
        val info = parse(YtDlpSamples.CAROUSEL, MediaProvider.INSTAGRAM, "https://www.instagram.com/p/post1/")

        assertEquals("Post by someone", info.title)
        assertEquals(listOf<Int?>(1280, null), info.variants.map { it.height })
        assertEquals("dash-video+dash-audio", specOf(info.variants.first().downloadUrl))
    }

    @Test
    fun `missing title falls back to provider and id`() {
        val info = parse("""{"id":"zzz","formats":[{"format_id":"18","ext":"mp4","vcodec":"a","acodec":"b","height":360}]}""")

        assertEquals("YouTube zzz", info.title)
    }

    @Test
    fun `format ids that could carry an option are dropped`() {
        val info = parse(
            """{"id":"x","formats":[
                {"format_id":"18","ext":"mp4","vcodec":"a","acodec":"b","height":360},
                {"format_id":"--exec rm","ext":"mp4","vcodec":"a","acodec":"b","height":720}
            ]}""",
        )

        assertEquals(listOf<Int?>(360), info.variants.map { it.height })
    }

    @Test
    fun `no usable media fails with NO_MEDIA`() {
        try {
            parse("""{"id":"x","title":"t","formats":[]}""")
            fail("expected ExtractionFailed")
        } catch (e: MediaResolveException.ExtractionFailed) {
            assertEquals(Reason.NO_MEDIA, e.reason)
            assertEquals(MediaProvider.YOUTUBE, e.provider)
        }
    }

    @Test
    fun `garbage output fails safely`() {
        try {
            parse("ERROR: something went wrong")
            fail("expected ExtractionFailed")
        } catch (e: MediaResolveException.ExtractionFailed) {
            assertEquals(Reason.NO_MEDIA, e.reason)
        }
        try {
            parse("{ not json }")
            fail("expected ExtractionFailed")
        } catch (e: MediaResolveException.ExtractionFailed) {
            assertEquals(Reason.OTHER, e.reason)
        }
    }

    @Test
    fun `stdout noise around the json is tolerated`() {
        val info = parse("[debug] hello\n" + YtDlpSamples.YOUTUBE + "\n")

        assertNotNull(info.title)
        assertTrue(info.variants.isNotEmpty())
    }
}
