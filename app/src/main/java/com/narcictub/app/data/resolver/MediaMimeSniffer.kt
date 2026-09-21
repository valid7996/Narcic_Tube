package com.narcictub.app.data.resolver

import java.nio.charset.StandardCharsets

/**
 * Magic-byte MIME sniffing over a small (≤ 4 KiB) response prefix — used
 * only when the server's declared Content-Type is missing or generic.
 * Recognizes real audio/video container signatures plus mislabeled HTML.
 * Anything unrecognized returns null (unknown stays unknown — no guessing).
 */
internal object MediaMimeSniffer {

    private val MP4_AUDIO_BRANDS = setOf("M4A ", "M4B ", "M4P ")

    fun sniff(prefix: ByteArray): String? {
        val size = prefix.size
        val ascii = { offset: Int, length: Int ->
            String(prefix, offset, length, StandardCharsets.ISO_8859_1)
        }

        // ISO BMFF (MP4/M4A): "ftyp" box at offset 4, brand at offset 8.
        if (size >= 12 && ascii(4, 4) == "ftyp") {
            return if (ascii(8, 4) in MP4_AUDIO_BRANDS) "audio/mp4" else "video/mp4"
        }
        // MP3: ID3v2 tag or MPEG audio frame sync (0xFFEx).
        if (size >= 3 && prefix[0] == 'I'.code.toByte() && prefix[1] == 'D'.code.toByte() &&
            prefix[2] == '3'.code.toByte()
        ) {
            return "audio/mpeg"
        }
        if (size >= 2 && (prefix[0].toInt() and 0xFF) == 0xFF && (prefix[1].toInt() and 0xE0) == 0xE0) {
            return "audio/mpeg"
        }
        // Matroska/WebM EBML header 0x1A45DFA3; DocType disambiguates.
        if (size >= 4 && prefix[0] == 0x1A.toByte() && prefix[1] == 0x45.toByte() &&
            prefix[2] == 0xDF.toByte() && prefix[3] == 0xA3.toByte()
        ) {
            val head = ascii(0, minOf(size, 64))
            return if (head.contains("webm")) "video/webm" else "video/x-matroska"
        }
        // Ogg container.
        if (size >= 4 && ascii(0, 4) == "OggS") return "audio/ogg"
        // RIFF family: WAVE and AVI share the "RIFF" magic.
        if (size >= 12 && ascii(0, 4) == "RIFF") {
            return when (ascii(8, 4)) {
                "WAVE" -> "audio/wav"
                "AVI " -> "video/x-msvideo"
                else -> null
            }
        }
        if (size >= 4 && ascii(0, 4) == "fLaC") return "audio/flac"
        // MPEG transport stream: 0x47 sync bytes at packet boundaries 0 and 188.
        if (size >= 189 && prefix[0] == 0x47.toByte() && prefix[188] == 0x47.toByte()) {
            return "video/mp2t"
        }
        // MPEG program stream pack header.
        if (size >= 4 && prefix[0] == 0x00.toByte() && prefix[1] == 0x00.toByte() &&
            prefix[2] == 0x01.toByte() && prefix[3] == 0xBA.toByte()
        ) {
            return "video/mpeg"
        }
        if (size >= 3 && ascii(0, 3) == "FLV") return "video/x-flv"

        // A page served without text/html must still be reported as a
        // webpage, not as a downloadable file.
        val text = ascii(0, minOf(size, 256)).trimStart().lowercase()
        if (text.startsWith("<!doctype html") || text.startsWith("<html")) return "text/html"

        return null
    }
}
