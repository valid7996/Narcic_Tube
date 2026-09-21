package com.narcictub.app.data.resolver

import java.nio.charset.StandardCharsets

/**
 * PHASE 18 — real MP4/M4A container metadata parsing over a bounded byte
 * prefix/tail. Pure and deterministic: no network, no reflection, no
 * allocation beyond small arrays. Every walk step is size-guarded so
 * malformed or truncated data can never loop or index out of bounds.
 *
 * What is extracted (all REAL, from the actual container bytes):
 *  - duration (moov/mvhd timescale+duration, ISO BMFF v0 and v1)
 *  - video dimensions (moov/trak/tkhd width/height, 16.16 fixed point)
 *  - container identity (ftyp major brand → "mp4"/"m4a")
 *
 * Anything absent, truncated or malformed stays null — never guessed.
 */
internal object Mp4MetadataParser {

    data class Mp4Metadata(
        val durationSeconds: Long?,
        val width: Int?,
        val height: Int?,
        val container: String?,
    )

    private const val MAX_CHILDREN = 64
    private const val MAX_WALK_DEPTH = 2 // moov children + trak children
    private val ISO = StandardCharsets.ISO_8859_1

    fun parse(bytes: ByteArray): Mp4Metadata? {
        if (bytes.size < 8) return null
        val container = readContainer(bytes)
        val moov = findBox(bytes, 0, bytes.size, "moov")
            ?: findByScan(bytes, "moov")
            ?: return null

        var duration: Long? = null
        var dims: Pair<Int, Int>? = null
        walkChildren(bytes, moov.payloadStart, moov.end, depth = 0) { type, payloadStart, payloadEnd ->
            when (type) {
                "mvhd" -> if (duration == null) duration = parseMvhd(bytes, payloadStart, payloadEnd)
                "trak" -> if (dims == null) {
                    walkChildren(bytes, payloadStart, payloadEnd, depth = 1) { trakType, trakStart, trakEnd ->
                        if (trakType == "tkhd" && dims == null) {
                            dims = parseTkhd(bytes, trakStart, trakEnd)
                        }
                    }
                }
            }
        }
        if (duration == null && dims == null && container == null) return null
        return Mp4Metadata(
            durationSeconds = duration,
            width = dims?.first,
            height = dims?.second,
            container = container,
        )
    }

    // ===== box model =====

    private class Box(val type: String, val payloadStart: Int, val end: Int)

    /**
     * Walks the direct children of a container box, invoking [visitor] per
     * child. Fully guarded: a child needs at least an 8-byte header to be
     * visited, truncated tails simply end the walk, and the number of
     * children is capped.
     */
    private inline fun walkChildren(
        bytes: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        visitor: (type: String, payloadStart: Int, payloadEnd: Int) -> Unit,
    ) {
        if (depth > MAX_WALK_DEPTH) return
        var offset = start
        var visited = 0
        while (offset + 8 <= end && visited < MAX_CHILDREN) {
            val size = readU32(bytes, offset)
            val type = fourcc(bytes, offset + 4) ?: return
            var header = 8
            var boxEnd = end.toLong()
            when {
                size == 1L -> {
                    // 64-bit largesize; needs 8 more header bytes.
                    if (offset + 16 > end) return
                    val large = readU64(bytes, offset + 8)
                    if (large < 16L) return
                    header = 16
                    boxEnd = minOf(end.toLong(), offset + large)
                }
                size < 8L -> return // invalid/corrupt — stop the walk
                else -> boxEnd = minOf(end.toLong(), offset + size)
            }
            val payloadStart = offset + header
            if (payloadStart < boxEnd) visitor(type, payloadStart, boxEnd.toInt())
            if (boxEnd <= offset) return // never progress backwards
            offset = boxEnd.toInt()
            visited++
        }
    }

    private fun findBox(bytes: ByteArray, start: Int, end: Int, type: String): Box? {
        var offset = start
        var visited = 0
        while (offset + 8 <= end && visited < MAX_CHILDREN) {
            val size = readU32(bytes, offset)
            val boxType = fourcc(bytes, offset + 4) ?: return null
            if (size < 8L) return null
            val boxEnd = minOf(end.toLong(), offset + size)
            if (boxType == type && offset + 8 < boxEnd) {
                return Box(type, offset + 8, boxEnd.toInt())
            }
            if (boxEnd <= offset) return null
            offset = boxEnd.toInt()
            visited++
        }
        return null
    }

    /**
     * Tail buffers can begin mid-box, so the top-level walk may miss moov.
     * Scans for the "moov" fourcc and validates the size field that must
     * precede it; a moov whose declared size overruns the buffer is
     * tolerated as a truncated moov (children are parsed up to the end).
     */
    private fun findByScan(bytes: ByteArray, type: String): Box? {
        val target = type.toByteArray(ISO)
        var i = 0
        while (i + 8 <= bytes.size) {
            if (bytes[i] == target[0] && bytes[i + 1] == target[1] &&
                bytes[i + 2] == target[2] && bytes[i + 3] == target[3]
            ) {
                val boxStart = i - 4
                if (boxStart >= 0) {
                    val size = readU32(bytes, boxStart)
                    if (size >= 8L) {
                        return Box(type, i + 4, bytes.size) // tolerate truncation
                    }
                }
            }
            i++
        }
        return null
    }

    // ===== mvhd: timescale + duration =====

    private fun parseMvhd(bytes: ByteArray, payloadStart: Int, payloadEnd: Int): Long? {
        if (payloadEnd - payloadStart < 12) return null
        val version = bytes[payloadStart].toInt() and 0xFF
        return when (version) {
            0 -> {
                val timescale = readU32(bytes, payloadStart + 4)
                val duration = readU32(bytes, payloadStart + 8)
                toSeconds(timescale, duration)
            }
            1 -> {
                if (payloadEnd - payloadStart < 28) return null
                val timescale = readU32(bytes, payloadStart + 16)
                val duration = readU64(bytes, payloadStart + 20)
                toSeconds(timescale, duration)
            }
            else -> null // unknown version — honest null
        }
    }

    private fun toSeconds(timescale: Long, duration: Long): Long? {
        if (timescale <= 0 || duration <= 0) return null
        return duration / timescale
    }

    // ===== tkhd: 16.16 fixed-point width/height =====

    private fun parseTkhd(bytes: ByteArray, payloadStart: Int, payloadEnd: Int): Pair<Int, Int>? {
        if (payloadEnd - payloadStart < 4) return null
        val version = bytes[payloadStart].toInt() and 0xFF
        val (widthOffset, heightOffset, minLength) = when (version) {
            0 -> Triple(76, 80, 84)
            1 -> Triple(88, 92, 96)
            else -> return null
        }
        if (payloadEnd - payloadStart < minLength) return null
        val width = fixedPoint16_16(readU32(bytes, payloadStart + widthOffset))
        val height = fixedPoint16_16(readU32(bytes, payloadStart + heightOffset))
        if (width == null || height == null) return null // audio tracks carry 0×0
        return width to height
    }

    private fun fixedPoint16_16(raw: Long): Int? {
        val value = Math.round(raw / 65536.0).toInt()
        return value.takeIf { it > 0 }
    }

    // ===== ftyp: container identity =====

    private fun readContainer(bytes: ByteArray): String? {
        val ftyp = findBox(bytes, 0, bytes.size, "ftyp") ?: return null
        if (ftyp.end - ftyp.payloadStart < 4) return null
        return when (val brand = String(bytes, ftyp.payloadStart, 4, ISO)) {
            "M4A ", "M4B ", "M4P " -> "m4a"
            else -> if (brand.isNotBlank()) "mp4" else null
        }
    }

    // ===== primitives =====

    private fun fourcc(bytes: ByteArray, offset: Int): String? {
        if (offset + 4 > bytes.size) return null
        return String(bytes, offset, 4, ISO)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value and Long.MAX_VALUE // clamp for arithmetic safety
    }
}
