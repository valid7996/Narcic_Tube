package com.narcictub.app.data.ytdlp

import com.narcictub.app.domain.model.MediaProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class YtDlpUrlTest {

    @Test
    fun `recognizes youtube hosts`() {
        listOf(
            "https://www.youtube.com/watch?v=1",
            "https://youtube.com/watch?v=1",
            "https://m.youtube.com/watch?v=1",
            "https://music.youtube.com/watch?v=1",
            "https://youtu.be/abc",
            "http://www.youtube.com/shorts/abc",
        ).forEach { assertEquals(it, MediaProvider.YOUTUBE, YtDlpUrl.providerOf(it)) }
    }

    @Test
    fun `recognizes instagram hosts`() {
        listOf(
            "https://www.instagram.com/p/Cabc123/",
            "https://instagram.com/reel/Cabc123/",
            "https://m.instagram.com/p/Cabc123/",
        ).forEach { assertEquals(it, MediaProvider.INSTAGRAM, YtDlpUrl.providerOf(it)) }
    }

    @Test
    fun `lookalikes credentials schemes and literals are not recognized`() {
        listOf(
            "https://youtube.com.evil.com/watch?v=1",
            "https://evilyoutube.com/watch?v=1",
            "https://instagram.com.evil.com/p/x/",
            "https://evilinstagram.com/p/x/",
            "https://user:pw@www.youtube.com/watch?v=1",
            "ftp://www.youtube.com/watch?v=1",
            "javascript:alert(1)",
            "https://127.0.0.1/watch?v=1",
            "https://localhost/",
            "not a url",
            "",
        ).forEach { assertEquals(it, MediaProvider.UNKNOWN, YtDlpUrl.providerOf(it)) }
    }

    @Test
    fun `format spec round-trips through the url fragment`() {
        val page = "https://www.youtube.com/watch?v=abc123"

        val url = YtDlpUrl.withFormat(page, "137+140")

        assertEquals("$page#nt-f=137+140", url)
        assertEquals(YtDlpUrl.Parsed(page, "137+140"), YtDlpUrl.parse(url))
    }

    @Test
    fun `withFormat replaces an existing fragment`() {
        val url = YtDlpUrl.withFormat("https://youtu.be/abc#t=30", "18")

        assertEquals("https://youtu.be/abc#nt-f=18", url)
    }

    @Test
    fun `urls without a spec parse to a null spec`() {
        assertEquals(YtDlpUrl.Parsed("https://youtu.be/abc", null), YtDlpUrl.parse("https://youtu.be/abc"))
        assertNull(YtDlpUrl.parse("https://youtu.be/abc#t=30").formatSpec)
    }

    @Test
    fun `unsafe specs are rejected on parse and on build`() {
        listOf(
            "https://youtu.be/abc#nt-f=--exec+rm",
            "https://youtu.be/abc#nt-f=a b",
            "https://youtu.be/abc#nt-f=a+b+c",
            "https://youtu.be/abc#nt-f=",
            "https://youtu.be/abc#nt-f=a;b",
        ).forEach { assertNull(it, YtDlpUrl.parse(it).formatSpec) }

        assertThrows(IllegalArgumentException::class.java) {
            YtDlpUrl.withFormat("https://youtu.be/abc", "x y")
        }
    }
}
