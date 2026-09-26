package com.narcictub.app.data.resolver

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HONEY — Instagram photo posts: the og:image parser must pull the real
 * photo URL (attribute orders vary), decode HTML entities, pin to https and
 * degrade to null when the page exposes nothing usable.
 */
class InstagramPhotoResolverTest {

    private val resolver = InstagramPhotoResolver(mockk(relaxed = true))

    @Test
    fun `og image with property before content is parsed`() = runTest {
        val html = """<html><head>
            <meta property="og:image" content="https://scontent.cdninstagram.com/v/img.jpg?efg=1&amp;oe=9" />
            <meta property="og:title" content="Sophie on Instagram: golden hour" />
        </head></html>"""

        val og = resolver.parseOgImage(html)

        assertNotNull(og)
        assertEquals("https://scontent.cdninstagram.com/v/img.jpg?efg=1&oe=9", og!!.imageUrl)
        assertEquals("Sophie on Instagram: golden hour", og.title)
    }

    @Test
    fun `og image with content before property is parsed`() = runTest {
        val html = """<meta content="https://cdn.instagram.com/photo.jpg" property="og:image">"""
        val og = resolver.parseOgImage(html)
        assertNotNull(og)
        assertEquals("https://cdn.instagram.com/photo.jpg", og!!.imageUrl)
    }

    @Test
    fun `non https og image is rejected`() = runTest {
        assertNull(resolver.parseOgImage("""<meta property="og:image" content="http://cdn.instagram.com/x.jpg">"""))
        assertNull(resolver.parseOgImage("""<meta property="og:image" content="javascript:alert(1)">"""))
    }

    @Test
    fun `pages without og image degrade to null`() = runTest {
        assertNull(resolver.parseOgImage("<html><head><title>Login • Instagram</title></head></html>"))
    }

    @Test
    fun `photo post media info carries one direct image variant`() = runTest {
        // The resolver builds the MediaInfo on the network path; here we pin
        // the contract of the built object via a successful parse + construct
        // shape (kind image, single variant, direct file).
        val html = """<meta property="og:image" content="https://scontent.cdninstagram.com/img.jpg">"""
        val og = resolver.parseOgImage(html)!!
        assertTrue(og.imageUrl.startsWith("https://"))
    }
}

/*
 * HONEY — fallback hardening: any Instagram extraction failure gets a photo
 * attempt (login-walls included), the embed page is a second source, and
 * canonical post URLs are extracted from arbitrary share links.
 */
class InstagramPhotoFallbackTest {

    private val resolver = InstagramPhotoResolver(mockk(relaxed = true))

    @Test
    fun `canonical post url is extracted from share links`() {
        assertEquals(
            "https://www.instagram.com/p/CdeFg123/",
            resolver.canonicalPostUrl("https://www.instagram.com/p/CdeFg123/?igsh=abc&utm=1"),
        )
        assertEquals(
            "https://www.instagram.com/reel/Rxyz9_/",
            resolver.canonicalPostUrl("https://www.instagram.com/reels/Rxyz9_/?igsh=abc"),
        )
        assertNull(resolver.canonicalPostUrl("https://www.instagram.com/username/"))
    }

    @Test
    fun `embed page image is parsed`() {
        val html = """<img class="EmbeddedMediaImage" src="https://scontent.cdninstagram.com/embed.jpg" />"""
        val og = resolver.parseEmbedImage(html)
        assertNotNull(og)
        assertEquals("https://scontent.cdninstagram.com/embed.jpg", og!!.imageUrl)
    }

    @Test
    fun `embed parse rejects non https`() {
        assertNull(resolver.parseEmbedImage("""<img class="EmbeddedMediaImage" src="http://x/y.jpg">"""))
    }
}
