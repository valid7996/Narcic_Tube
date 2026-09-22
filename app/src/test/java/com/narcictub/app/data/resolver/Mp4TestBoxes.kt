package com.narcictub.app.data.resolver

import java.io.ByteArrayOutputStream

/**
 * PHASE 18 — test-only ISO BMFF box builders (real layout) shared by the
 * Mp4MetadataParser tests and the DirectMediaResolver integration tests.
 */
internal fun mp4Box(type: String, vararg payload: ByteArray): ByteArray {
    val body = ByteArrayOutputStream().apply { payload.forEach { write(it) } }
    val size = 8 + body.size()
    val out = ByteArrayOutputStream()
    out.write(
        byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(),
        ),
    )
    out.write(type.toByteArray(Charsets.ISO_8859_1))
    out.write(body.toByteArray())
    return out.toByteArray()
}

internal fun mp4Ftyp(brand: String): ByteArray =
    mp4Box("ftyp", brand.toByteArray(Charsets.ISO_8859_1))

/** mvhd v0: version(1) flags(3) ctime(4) mtime(4) timescale(4) duration(4). */
internal fun mp4MvhdV0(timescale: Int, duration: Int): ByteArray {
    val p = ByteArray(20)
    p[0] = 0
    writeU32(p, 4, timescale.toLong())
    writeU32(p, 8, duration.toLong())
    return mp4Box("mvhd", p)
}

/** mvhd v1: version(1) flags(3) ctime(8) mtime(8) timescale(4) duration(8). */
internal fun mp4MvhdV1(timescale: Int, duration: Long): ByteArray {
    val p = ByteArray(28)
    p[0] = 1
    writeU32(p, 16, timescale.toLong())
    writeU64(p, 20, duration)
    return mp4Box("mvhd", p)
}

/** tkhd v0: …duration(4) reserved(8) layer(2) alt(2) volume(2) res(2) matrix(36) width(4) height(4). */
internal fun mp4TkhdV0(width: Int, height: Int): ByteArray {
    val p = ByteArray(96)
    p[0] = 0
    writeU32(p, 76, width.toLong() shl 16)
    writeU32(p, 80, height.toLong() shl 16)
    return mp4Box("tkhd", p)
}

/** tkhd v1 shifts the fixed-point fields by 12 bytes (64-bit timestamps). */
internal fun mp4TkhdV1(width: Int, height: Int): ByteArray {
    val p = ByteArray(108)
    p[0] = 1
    writeU32(p, 88, width.toLong() shl 16)
    writeU32(p, 92, height.toLong() shl 16)
    return mp4Box("tkhd", p)
}

internal fun mp4Trak(vararg children: ByteArray) = mp4Box("trak", *children)

internal fun mp4Moov(vararg children: ByteArray) = mp4Box("moov", *children)

private fun writeU32(target: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 4) {
        target[offset + i] = ((value ushr (8 * (3 - i))) and 0xFF).toByte()
    }
}

private fun writeU64(target: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
        target[offset + i] = ((value ushr (8 * (7 - i))) and 0xFF).toByte()
    }
}
