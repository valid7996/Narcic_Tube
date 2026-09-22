package com.narcictub.app.data.resolver

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PHASE 18 — real MP4 container metadata parsing over crafted ISO BMFF
 * bytes. Every test exercises the actual box-walking/field-offset logic
 * that production uses, including malformed and truncated inputs.
 */
class Mp4MetadataParserTest {

    // ===== happy paths =====

    @Test
    fun `mvhd v0 yields real duration`() {
        val bytes = mp4Moov(mp4MvhdV0(timescale = 1000, duration = 205_000))
        val metadata = Mp4MetadataParser.parse(bytes)!!
        assertEquals(205L, metadata.durationSeconds)
        assertNull(metadata.width)
        assertNull(metadata.height)
    }

    @Test
    fun `mvhd v1 64-bit duration yields real duration`() {
        val bytes = mp4Moov(mp4MvhdV1(timescale = 90000, duration = 9_000_000L))
        assertEquals(100L, Mp4MetadataParser.parse(bytes)!!.durationSeconds)
    }

    @Test
    fun `video track dimensions are parsed from tkhd v0`() {
        val bytes = mp4Moov(mp4MvhdV0(1000, 5_000), mp4Trak(mp4TkhdV0(1920, 1080)))
        val metadata = Mp4MetadataParser.parse(bytes)!!
        assertEquals(5L, metadata.durationSeconds)
        assertEquals(1920, metadata.width)
        assertEquals(1080, metadata.height)
    }

    @Test
    fun `tkhd v1 dimensions are parsed`() {
        val bytes = mp4Moov(mp4MvhdV0(1000, 5_000), mp4Trak(mp4TkhdV1(1280, 720)))
        val metadata = Mp4MetadataParser.parse(bytes)!!
        assertEquals(1280, metadata.width)
        assertEquals(720, metadata.height)
    }

    @Test
    fun `audio track with zero dimensions yields null resolution but real duration`() {
        val bytes = mp4Moov(mp4MvhdV0(1000, 12_345), mp4Trak(mp4TkhdV0(0, 0)))
        val metadata = Mp4MetadataParser.parse(bytes)!!
        assertEquals(12L, metadata.durationSeconds)
        assertNull(metadata.width)
        assertNull(metadata.height)
    }

    @Test
    fun `ftyp brand identifies the container`() {
        val m4a = Mp4MetadataParser.parse(mp4Ftyp("M4A ") + mp4Moov(mp4MvhdV0(1000, 60)))!!
        assertEquals("m4a", m4a.container)
        val mp4 = Mp4MetadataParser.parse(mp4Ftyp("isom") + mp4Moov(mp4MvhdV0(1000, 60)))!!
        assertEquals("mp4", mp4.container)
    }

    // ===== truncation / malformed input =====

    @Test
    fun `moov without mvhd yields no duration`() {
        val metadata = Mp4MetadataParser.parse(mp4Moov(mp4Ftyp("isom")))
        // Nothing real was extracted → null, never a fabricated duration.
        assertNull(metadata?.durationSeconds)
        assertNull(metadata?.width)
    }

    @Test
    fun `no moov at all yields null`() {
        assertNull(Mp4MetadataParser.parse(mp4Ftyp("isom")))
    }

    @Test
    fun `truncated moov still yields the readable mvhd duration`() {
        // Real-world tail-read case: moov declared large but the buffer ends
        // right after mvhd — the walk must tolerate the truncation.
        val full = mp4Moov(mp4MvhdV0(1000, 42_000), mp4Trak(mp4TkhdV0(640, 360)))
        val truncated = full.copyOfRange(0, full.size - 40)
        val metadata = Mp4MetadataParser.parse(truncated)!!
        assertEquals(42L, metadata.durationSeconds)
    }

    @Test
    fun `malformed box size stops the walk without crashing`() {
        val broken = ByteArray(64)
        // Box declares size 2 (< minimum 8) → walk stops immediately.
        broken[0] = 0; broken[1] = 0; broken[2] = 0; broken[3] = 2
        "moov".toByteArray(Charsets.ISO_8859_1).copyInto(broken, 4)
        assertNull(Mp4MetadataParser.parse(broken))
    }

    @Test
    fun `garbage bytes yield null`() {
        val garbage = ByteArray(4096) { (it * 31 % 251).toByte() }
        assertNull(Mp4MetadataParser.parse(garbage))
    }

    @Test
    fun `zero timescale never divides by zero`() {
        val metadata = Mp4MetadataParser.parse(mp4Moov(mp4MvhdV0(0, 5_000)))
        assertNull("nothing real extracted - result itself is null", metadata)
        assertNull(metadata?.durationSeconds)
    }

    @Test
    fun `largesize box is skipped safely`() {
        // A box with size==1 uses the 64-bit largesize field; the walker
        // must skip the declared length and still find the mvhd after it.
        val large = ByteArrayOutputStream()
        val hugePayload = ByteArray(64)
        val size64 = 16L + hugePayload.size
        large.write(byteArrayOf(0, 0, 0, 1))
        large.write("free".toByteArray(Charsets.ISO_8859_1))
        for (i in 0 until 8) {
            large.write(((size64 ushr (8 * (7 - i))) and 0xFF).toInt())
        }
        large.write(hugePayload)
        val bytes = mp4Moov(large.toByteArray(), mp4MvhdV0(1000, 77_000))
        assertEquals(77L, Mp4MetadataParser.parse(bytes)!!.durationSeconds)
    }
}
