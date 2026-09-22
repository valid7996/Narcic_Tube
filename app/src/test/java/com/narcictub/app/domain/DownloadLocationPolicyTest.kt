package com.narcictub.app.domain

import com.narcictub.app.domain.model.DownloadLocation
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadLocationPolicyTest {

    @Test
    fun `video mime always routes to Movies regardless of the manual setting`() {
        DownloadLocation.entries.forEach { setting ->
            assertEquals(DownloadLocation.MOVIES, DownloadLocationPolicy.effectiveLocation("video/mp4", setting))
        }
    }

    @Test
    fun `audio mime always routes to Music regardless of the manual setting`() {
        DownloadLocation.entries.forEach { setting ->
            assertEquals(DownloadLocation.MUSIC, DownloadLocationPolicy.effectiveLocation("audio/mp4", setting))
        }
    }

    @Test
    fun `anything else respects the manual setting`() {
        assertEquals(DownloadLocation.DOWNLOADS, DownloadLocationPolicy.effectiveLocation("application/pdf", DownloadLocation.DOWNLOADS))
        assertEquals(DownloadLocation.DCIM, DownloadLocationPolicy.effectiveLocation("image/jpeg", DownloadLocation.DCIM))
        assertEquals(DownloadLocation.DOWNLOADS, DownloadLocationPolicy.effectiveLocation(null, DownloadLocation.DOWNLOADS))
    }

    @Test
    fun `mime type parameters and casing do not affect classification`() {
        assertEquals(DownloadLocation.MOVIES, DownloadLocationPolicy.effectiveLocation("VIDEO/MP4; codecs=avc1", DownloadLocation.DOWNLOADS))
        assertEquals(DownloadLocation.MUSIC, DownloadLocationPolicy.effectiveLocation(" audio/mp4 ", DownloadLocation.DOWNLOADS))
    }

    @Test
    fun `every relative path is nested under the app folder`() {
        assertEquals("Download/${DownloadLocationPolicy.APP_FOLDER_NAME}/", DownloadLocationPolicy.relativePath(DownloadLocation.DOWNLOADS))
        assertEquals("Music/${DownloadLocationPolicy.APP_FOLDER_NAME}/", DownloadLocationPolicy.relativePath(DownloadLocation.MUSIC))
        assertEquals("Movies/${DownloadLocationPolicy.APP_FOLDER_NAME}/", DownloadLocationPolicy.relativePath(DownloadLocation.MOVIES))
        assertEquals("DCIM/${DownloadLocationPolicy.APP_FOLDER_NAME}/", DownloadLocationPolicy.relativePath(DownloadLocation.DCIM))
    }
}
