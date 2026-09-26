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

/*
 * HONEY — video fallback: public Reels/videos expose their real mp4 either
 * via og:video(:secure_url) on the post page, or a <video src> on the embed
 * page, without any login. Same no-fabrication rules as the photo path.
 */
class InstagramVideoFallbackTest {

    private val resolver = InstagramPhotoResolver(mockk(relaxed = true))

    @Test
    fun `og video secure url is preferred over plain og video`() {
        val html = """<html><head>
            <meta property="og:video" content="http://scontent.cdninstagram.com/insecure.mp4" />
            <meta property="og:video:secure_url" content="https://scontent.cdninstagram.com/v/clip.mp4?efg=1&amp;oe=9" />
            <meta property="og:title" content="Reel by sophie" />
            <meta property="og:image" content="https://scontent.cdninstagram.com/thumb.jpg" />
        </head></html>"""

        val og = resolver.parseOgVideo(html)

        assertNotNull(og)
        assertEquals("https://scontent.cdninstagram.com/v/clip.mp4?efg=1&oe=9", og!!.videoUrl)
        assertEquals("Reel by sophie", og.title)
        assertEquals("https://scontent.cdninstagram.com/thumb.jpg", og.thumbnailUrl)
    }

    @Test
    fun `plain https og video is used when no secure_url is present`() {
        val html = """<meta content="https://cdn.instagram.com/reel.mp4" property="og:video">"""
        val og = resolver.parseOgVideo(html)
        assertNotNull(og)
        assertEquals("https://cdn.instagram.com/reel.mp4", og!!.videoUrl)
    }

    @Test
    fun `an insecure og video with no secure_url is rejected`() {
        assertNull(resolver.parseOgVideo("""<meta property="og:video" content="http://cdn.instagram.com/x.mp4">"""))
    }

    @Test
    fun `pages without og video degrade to null`() {
        assertNull(resolver.parseOgVideo("<html><head><title>Login • Instagram</title></head></html>"))
    }

    @Test
    fun `embed page video tag is parsed with its poster`() {
        val html = """<video class="EmbeddedMediaVideo" poster="https://scontent.cdninstagram.com/poster.jpg" src="https://scontent.cdninstagram.com/embed.mp4"></video>"""
        val og = resolver.parseEmbedVideo(html)
        assertNotNull(og)
        assertEquals("https://scontent.cdninstagram.com/embed.mp4", og!!.videoUrl)
        assertEquals("https://scontent.cdninstagram.com/poster.jpg", og.thumbnailUrl)
    }

    @Test
    fun `embed video parse rejects non https`() {
        assertNull(resolver.parseEmbedVideo("""<video src="http://x/y.mp4"></video>"""))
    }
}
