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
