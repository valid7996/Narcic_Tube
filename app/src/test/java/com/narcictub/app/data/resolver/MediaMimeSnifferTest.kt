package com.narcictub.app.data.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Magic-byte sniffing table: real container signatures only; anything
 * unrecognized is null (unknown stays unknown) and mislabeled HTML is
 * detected so a webpage is never reported as a downloadable file.
 */
class MediaMimeSnifferTest {

    private fun bytes(vararg ints: Int) = ByteArray(ints.size) { i -> ints[i].toByte() }

    private fun asciiPrefix(s: String, size: Int = 64): ByteArray {
        val data = ByteArray(size)
        s.toByteArray().copyInto(data)
        return data
    }

    @Test
    fun `mp4 isom brand is video`() {
        val data = ByteArray(64)
        "ftyp".toByteArray().copyInto(data, 4)
        "isom".toByteArray().copyInto(data, 8)
        assertEquals("video/mp4", MediaMimeSniffer.sniff(data))
    }

    @Test
    fun `mp4 audio brand M4A is audio`() {
        val data = ByteArray(64)
        "ftyp".toByteArray().copyInto(data, 4)
        "M4A ".toByteArray().copyInto(data, 8)
        assertEquals("audio/mp4", MediaMimeSniffer.sniff(data))
    }

    @Test
    fun `id3 tag is mp3`() {
        assertEquals("audio/mpeg", MediaMimeSniffer.sniff(asciiPrefix("ID3\u0003\u0000")))
    }

    @Test
    fun `mpeg frame sync is mp3`() {
        assertEquals("audio/mpeg", MediaMimeSniffer.sniff(bytes(0xFF, 0xFB, 0x90, 0x00)))
    }

    @Test
    fun `ebml with webm doctype is webm`() {
        val data = bytes(0x1A, 0x45, 0xDF, 0xA3) + asciiPrefix("...webm...", 60)
        assertEquals("video/webm", MediaMimeSniffer.sniff(data))
    }

    @Test
    fun `ebml without webm is matroska`() {
        val data = bytes(0x1A, 0x45, 0xDF, 0xA3) + asciiPrefix("...matroska...", 60)
        assertEquals("video/x-matroska", MediaMimeSniffer.sniff(data))
    }

    @Test
    fun `ogg container is audio`() {
        assertEquals("audio/ogg", MediaMimeSniffer.sniff(asciiPrefix("OggS\u0000\u0002")))
    }

    @Test
    fun `riff wave is wav`() {
        val data = asciiPrefix("RIFFxxxxWAVEfmt ", 64)
        assertEquals("audio/wav", MediaMimeSniffer.sniff(data))
    }

    @Test
    fun `riff avi is avi video`() {
        val data = asciiPrefix("RIFFxxxxAVI LIST", 64)
        assertEquals("video/x-msvideo", MediaMimeSniffer.sniff(data))
    }

    @Test
    fun `flac magic is flac`() {
        assertEquals("audio/flac", MediaMimeSniffer.sniff(asciiPrefix("fLaC\u0000\u0000\u0000")))
    }

    @Test
    fun `transport stream sync at packet boundaries is mpegts`() {
        val data = ByteArray(200)
        data[0] = 0x47
        data[188] = 0x47
        assertEquals("video/mp2t", MediaMimeSniffer.sniff(data))
    }

    @Test
    fun `mpeg program stream pack header is mpeg`() {
        assertEquals("video/mpeg", MediaMimeSniffer.sniff(bytes(0x00, 0x00, 0x01, 0xBA)))
    }

    @Test
    fun `flv magic is flv video`() {
        assertEquals("video/x-flv", MediaMimeSniffer.sniff(asciiPrefix("FLV\u0001")))
    }

    @Test
    fun `html doctype is detected as a webpage`() {
        assertEquals("text/html", MediaMimeSniffer.sniff(asciiPrefix("<!DOCTYPE html><html>")))
    }

    @Test
    fun `leading whitespace html is still a webpage`() {
        assertEquals("text/html", MediaMimeSniffer.sniff(asciiPrefix("  \n<html><body>hi</body>")))
    }

    @Test
    fun `unrecognized bytes stay unknown`() {
        assertNull(MediaMimeSniffer.sniff(bytes(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)))
    }

    @Test
    fun `empty prefix stays unknown`() {
        assertNull(MediaMimeSniffer.sniff(ByteArray(0)))
    }

    @Test
    fun `truncated container does not false-positive`() {
        // Only 6 bytes: "ftyp" check needs 12.
        assertNull(MediaMimeSniffer.sniff(asciiPrefix("xxftyp")))
    }
}
