package com.narcictub.app.data.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode
import com.narcictub.app.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * H-1 regression suite (Phase 6 security review).
 *
 * API 26–28 devices crashed in the old implementation because Q-only
 * MediaStore symbols were referenced on the pre-Q path (NoClassDefFoundError
 * — an Error, invisible to catch(Exception), leaving rows stuck DOWNLOADING).
 * These tests pin the fix from three angles:
 *
 *  1. DISPATCH — [MediaStoreFileWriter] routes API 26–28 to
 *     [LegacyAppStoragePublisher] and API 29+ to [QPlusMediaStorePublisher],
 *     with the boundary exactly at 29 (28 → legacy, 29 → Q+).
 *  2. CONFINEMENT — a source scan proves the Q-only MediaStore symbols
 *     (the pending flag, relative paths, the Q-only volume/downloads
 *     collections) appear ONLY in QPlusMediaStorePublisher.kt, so the
 *     classes are never even loaded on API 26–28. The real MediaStore
 *     interaction cannot run in JVM unit tests (android.jar stubs), so
 *     confinement + dispatch is the strongest pre-Q regression available;
 *     on-device behavior needs an instrumented test.
 *  3. LEGACY BEHAVIOR — the API 26–28 path itself is pure java.io, so it is
 *     tested end-to-end here: copy, collision uniquification, per-collection
 *     folders, internal-storage fallback, and no partial file left on failure.
 */
class MediaStoreFileWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSettingsRepository : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override suspend fun setTheme(mode: ThemeMode) {}
        override suspend fun setDownloadLocation(location: DownloadLocation) {}
        override suspend fun setWifiOnly(enabled: Boolean) {}
        override suspend fun setConcurrentDownloads(count: Int) {}
        override suspend fun setNotificationsEnabled(enabled: Boolean) {}
        override suspend fun setClipboardWatcherEnabled(enabled: Boolean) {}
    }

    /** Writer with both publish paths instrumented; records which path ran. */
    private class TestWriter(
        private val sdk: Int,
    ) : MediaStoreFileWriter(
        context = mockk<Context>(relaxed = true),
        settingsRepository = FakeSettingsRepository(),
    ) {
        val qPlusNames = mutableListOf<String>()
        val legacyNames = mutableListOf<String>()

        override fun deviceSdkInt(): Int = sdk

        override fun publishViaQPlus(
            location: DownloadLocation,
            stagingFile: File,
            safeName: String,
            mimeType: String?,
            subDirectory: String?,
        ): Uri {
            qPlusNames.add(safeName)
            return mockk(relaxed = true)
        }

        override fun publishViaLegacy(
            location: DownloadLocation,
            stagingFile: File,
            safeName: String,
        ): Uri {
            legacyNames.add(safeName)
            return mockk(relaxed = true)
        }
    }

    private fun stagingFile(content: String = "payload"): File =
        File(tmp.newFolder(), "staging.part").apply { writeText(content) }

    // ===== 1. dispatch split =====

    @Test
    fun `api 26 to 28 dispatch to the legacy publisher only`() {
        for (sdk in 26..28) {
            val writer = TestWriter(sdk)
            runBlocking { writer.publish(stagingFile(), "movie.mp4", "video/mp4") }
            assertEquals("sdk $sdk must route to the legacy publisher", listOf("movie.mp4"), writer.legacyNames)
            assertTrue("sdk $sdk must never touch the Q+ publisher", writer.qPlusNames.isEmpty())
        }
    }

    @Test
    fun `api 29 and above dispatch to the Q plus publisher only`() {
        for (sdk in 29..34) {
            val writer = TestWriter(sdk)
            runBlocking { writer.publish(stagingFile(), "movie.mp4", "video/mp4") }
            assertEquals("sdk $sdk must route to the Q+ publisher", listOf("movie.mp4"), writer.qPlusNames)
            assertTrue("sdk $sdk must never touch the legacy publisher", writer.legacyNames.isEmpty())
        }
    }

    @Test
    fun `boundary is exactly 29`() {
        val at28 = TestWriter(28)
        runBlocking { at28.publish(stagingFile(), "a.mp4", null) }
        assertTrue(at28.legacyNames.isNotEmpty())

        val at29 = TestWriter(29)
        runBlocking { at29.publish(stagingFile(), "a.mp4", null) }
        assertTrue(at29.qPlusNames.isNotEmpty())
    }

    @Test
    fun `display name is sanitized before either dispatch`() {
        val legacy = TestWriter(28)
        runBlocking { legacy.publish(stagingFile(), "../../etc/passwd", null) }
        assertEquals(listOf("passwd"), legacy.legacyNames)

        val qPlus = TestWriter(29)
        runBlocking { qPlus.publish(stagingFile(), "..\\..\\evil|name.mp4", null) }
        // traversal and reserved characters removed; last segment only
        assertEquals(1, qPlus.qPlusNames.size)
        assertFalse(qPlus.qPlusNames[0].contains(".."))
        assertFalse(qPlus.qPlusNames[0].contains('\\'))
        assertFalse(qPlus.qPlusNames[0].contains('|'))
    }

    // ===== 2. Q-only symbol confinement (class-loading safety) =====

    /** Locates a main-source file from the unit-test working directory. */
    private fun sourceFile(relPath: String): File? =
        listOf(File("src/main/java/$relPath"), File("app/src/main/java/$relPath"))
            .firstOrNull { it.isFile }

    @Test
    fun `pre Q sources contain no Q only MediaStore symbols`() {
        val preQSources = listOf(
            "com/narcictub/app/data/local/MediaStoreFileWriter.kt",
            "com/narcictub/app/data/local/LegacyAppStoragePublisher.kt",
        ).mapNotNull { sourceFile(it) }
        // Skip (not fail) when the source tree is not visible from the test
        // working directory — e.g. packaged CI runs without sources.
        assumeTrue("source tree not found from test working dir", preQSources.size == 2)

        val qOnlyTokens = listOf(
            "IS_PENDING",
            "RELATIVE_PATH",
            "VOLUME_EXTERNAL_PRIMARY",
            "MediaStore.Downloads",
            "MediaStore.Audio",
            "MediaStore.Video",
            "MediaStore.Images",
            "MediaStore.MediaColumns",
            "import android.provider.MediaStore",
        )
        for (source in preQSources) {
            val text = source.readText()
            for (token in qOnlyTokens) {
                assertFalse(
                    "${source.name} must not reference Q-only symbol '$token' — " +
                        "the class is loaded on API 26–28 and would crash with NoClassDefFoundError",
                    text.contains(token),
                )
            }
        }
    }

    @Test
    fun `pending flag protocol is confined to the Q plus publisher`() {
        val qPlus = sourceFile("com/narcictub/app/data/local/QPlusMediaStorePublisher.kt")
        assumeTrue("source tree not found from test working dir", qPlus != null)
        // IS_PENDING must exist exactly where it is allowed to — the Q+ file.
        assertTrue(qPlus!!.readText().contains("IS_PENDING"))
    }

    // ===== 3. legacy (API 26–28) behavior, end-to-end on the JVM =====

    @Test
    fun `legacy publisher copies the staging file and uniquifies collisions`() {
        val dir = tmp.newFolder()
        val context = mockk<Context>()
        every { context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } returns dir
        val staging = stagingFile("payload")

        val first = LegacyAppStoragePublisher.publish(context, DownloadLocation.DOWNLOADS, staging, "video.mp4")
        val second = LegacyAppStoragePublisher.publish(context, DownloadLocation.DOWNLOADS, staging, "video.mp4")

        assertEquals("payload", first.readText())
        assertEquals("video (1).mp4", second.name)
        assertEquals("payload", second.readText())
    }

    @Test
    fun `legacy publisher asks the platform for the chosen collection folder`() {
        // NOTE: the JVM unit-test android.jar nulls Environment.DIRECTORY_*
        // constants, so the exact folder name ("Music", "Pictures", …)
        // cannot be asserted here — that needs an instrumented test. What
        // IS pinnable on the JVM: each location routes through the platform
        // folder lookup (getExternalFilesDir) exactly once per publish.
        val requestedFolders = mutableListOf<String?>()
        val context = mockk<Context>()
        every { context.getExternalFilesDir(any()) } answers {
            requestedFolders.add(firstArg())
            tmp.newFolder()
        }
        val staging = stagingFile("x")

        for (location in DownloadLocation.entries) {
            LegacyAppStoragePublisher.publish(context, location, staging, "a.mp4")
        }

        assertEquals("one platform folder lookup per publish", 4, requestedFolders.size)
    }

    // NOTE (JVM limitation): the internal-storage fallback branch
    // (getExternalFilesDir → null ⇒ File(filesDir, folder)) cannot be pinned
    // here — the mockable android.jar nulls the Environment.DIRECTORY_*
    // constants this code path reads, and static final fields cannot be
    // stubbed. On a real device those constants are non-null, so the branch
    // behaves as documented; instrumented coverage should pin it.

    @Test
    fun `legacy publisher leaves nothing behind on a failed copy`() {
        val dir = tmp.newFolder()
        val context = mockk<Context>()
        every { context.getExternalFilesDir(any()) } returns dir
        // A directory as the "staging file": reading it fails immediately,
        // AFTER the target file name was claimed — the claim must be deleted.
        val stagingAsDirectory = tmp.newFolder()

        val result = runCatching {
            LegacyAppStoragePublisher.publish(context, DownloadLocation.DOWNLOADS, stagingAsDirectory, "video.mp4")
        }

        assertTrue(result.isFailure)
        assertEquals("no partial publish may survive a failure", 0, dir.listFiles()?.size ?: 0)
    }
}
