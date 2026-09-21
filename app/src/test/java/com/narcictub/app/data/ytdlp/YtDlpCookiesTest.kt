package com.narcictub.app.data.ytdlp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpCookiesTest {

    @Test
    fun `netscape header is accepted`() {
        assertTrue(YtDlpCookies.looksLikeNetscapeCookies("# Netscape HTTP Cookie File\n"))
    }

    @Test
    fun `seven column tab separated lines are accepted`() {
        val line = ".instagram.com\tTRUE\t/\tTRUE\t1893456000\tsessionid\tabc"

        assertTrue(YtDlpCookies.looksLikeNetscapeCookies(line))
    }

    @Test
    fun `arbitrary text and json are rejected`() {
        assertFalse(YtDlpCookies.looksLikeNetscapeCookies("hello world"))
        assertFalse(YtDlpCookies.looksLikeNetscapeCookies("""[{"name":"sessionid","value":"abc"}]"""))
        assertFalse(YtDlpCookies.looksLikeNetscapeCookies(""))
    }
}
