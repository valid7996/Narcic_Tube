package com.narcictub.app.data.downloader

import android.content.Context
import com.narcictub.app.data.local.MediaStoreFileWriter
import com.narcictub.app.domain.downloader.DownloadException
import com.narcictub.app.domain.downloader.FileDownloader
import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.FileNameSanitizer
import com.narcictub.app.domain.repository.DownloadRepository
import com.narcictub.app.domain.repository.HistoryRepository
import com.narcictub.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete DownloadRepository: an injected worker scope drives queued
 * downloads, bounded by a Semaphore sized from the concurrentDownloads
 * setting (1..8).
 *
 * CONCURRENCY POLICY (documented):
 *  - one app-lifetime CoroutineScope (provided by DataModule: SupervisorJob
 *    + Dispatchers.Default); each queued item is launched as a child Job
 *    (tracked in [activeJobs]) that FIRST acquires a semaphore permit, then
 *    streams. At most `concurrentDownloads` transfers run at once; QUEUED
 *    items hold no permit and no thread
 *  - [cancel] cancels the item's Job wherever it is (waiting for a permit or
 *    mid-stream). Cancellation BEFORE the permit is caught at the launch
 *    boundary; cancellation mid-stream is caught inside runDownload — both
 *    paths write CANCELLED under [NonCancellable] (a plain suspend call in
 *    a cancelled coroutine would abort before writing), delete the staging
 *    file, and clear progress. CANCELLED is thus distinguishable from FAILED
 *  - progress is throttled by the downloader and coalesced into a StateFlow
 *    map keyed by history id
 *  - no Thread.sleep, no custom thread pool, no Main-thread blocking
 *
 * STATUS DISCIPLINE: an item is only COMPLETED after the MediaStore publish
 * succeeds and localUri/size are written; the staging file is deleted only
 * after that full commit. Any failure deletes the staging file and marks
 * the row FAILED (or CANCELLED on user cancel).
 */
@Singleton
open class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: FileDownloader,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val mediaStoreWriter: MediaStoreFileWriter,
    private val workScope: CoroutineScope,
) : DownloadRepository {

    private val activeJobs = mutableMapOf<Long, Job>()
    private val waitingIds = ArrayDeque<Long>()

    private var semaphore = Semaphore(permits = 1)
    private var semaphoreCapacity = 1

    private val _progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    override val progress: StateFlow<Map<Long, DownloadProgress>> = _progress.asStateFlow()

    override fun observeDownloads(): Flow<List<HistoryItem>> =
        historyRepository.observeHistory()

    override suspend fun enqueue(sourceUrl: String): Long {
        val id = historyRepository.add(
            HistoryItem(
                sourceUrl = sourceUrl,
                title = FileNameSanitizer.fromUrl(sourceUrl) ?: "download",
                status = DownloadStatus.QUEUED,
                createdAt = Instant.now(),
            ),
        )
        synchronized(this) { waitingIds.addLast(id) }
        workScope.launch { pumpQueue() }
        return id
    }

    override suspend fun cancel(id: Long) {
        val job = synchronized(this) { activeJobs[id] }
        if (job != null) {
            job.cancel()
            return
        }
        // Not active: remove from the wait queue and mark CANCELLED directly.
        val removed = synchronized(this) { waitingIds.remove(id) }
        if (removed) {
            historyRepository.updateStatus(id, DownloadStatus.CANCELLED)
            _progress.update { it - id }
        } else {
            // Race fallback: a row may still be QUEUED in DB (e.g. process
            // restarted mid-queue) — flip it if untouched.
            val item = runCatching { historyRepository.get(id) }.getOrNull()
            if (item != null && item.status == DownloadStatus.QUEUED) {
                historyRepository.updateStatus(id, DownloadStatus.CANCELLED)
                _progress.update { it - id }
            }
        }
    }

    /**
     * Drains [waitingIds]: launches one child Job per queued id; each Job
     * acquires the concurrency permit before any work. Cancellation before
     * the permit is granted is caught here so the row never stays QUEUED.
     */
    private suspend fun pumpQueue() {
        // Adopt the configured concurrency (rebuild semaphore on change).
        val capacity = settingsRepository.settings.first().concurrentDownloads
        if (capacity != semaphoreCapacity) {
            semaphore = Semaphore(permits = capacity)
            semaphoreCapacity = capacity
        }

        while (true) {
            val id = synchronized(this) { waitingIds.removeFirstOrNull() } ?: return
            val job = workScope.launch {
                try {
                    semaphore.withPermit { runDownload(id) }
                } catch (e: CancellationException) {
                    // Cancelled while waiting for the permit — no staging
                    // file exists yet; just record the cancel.
                    withContext(NonCancellable) {
                        runCatching { historyRepository.updateStatus(id, DownloadStatus.CANCELLED) }
                    }
                    _progress.update { it - id }
                }
            }
            synchronized(this) { activeJobs[id] = job }
        }
    }

    private suspend fun runDownload(id: Long) {
        val item = historyRepository.get(id) ?: return
        if (item.status != DownloadStatus.QUEUED) return

        historyRepository.updateStatus(id, DownloadStatus.DOWNLOADING)
        _progress.update { it + (id to DownloadProgress(downloadedBytes = 0, totalBytes = null)) }

        val stagingFile = stagingFileFor(id)

        try {
            val result = downloader.download(item.sourceUrl, stagingFile) { p ->
                _progress.update { it + (id to p) }
            }

            // Commit phase: publish to MediaStore BEFORE marking complete.
            val displayName = item.fileName ?: item.title
            val published = mediaStoreWriter.publish(
                stagingFile = stagingFile,
                displayName = displayName,
                mimeType = result.contentType,
            )

            historyRepository.updateLocalUri(id, published.toString())
            historyRepository.updateSizeBytes(id, result.bytesDownloaded)
            historyRepository.updateStatus(
                id,
                DownloadStatus.COMPLETED,
                completedAt = Instant.now(),
            )
            // Staging file removed only after full commit.
            stagingFile.delete()
            _progress.update { it - id }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { historyRepository.updateStatus(id, DownloadStatus.CANCELLED) }
                runCatching { stagingFile.delete() }
            }
            _progress.update { it - id }
            throw e
        } catch (e: Exception) {
            runCatching { stagingFile.delete() }
            val message = when (e) {
                is DownloadException -> e.message
                else -> "Download failed"
            }
            runCatching { historyRepository.updateStatus(id, DownloadStatus.FAILED, errorMessage = message) }
            _progress.update { it - id }
        } finally {
            synchronized(this) { activeJobs.remove(id) }
        }
    }

    /** Root of the shared staging directory. Overridable for tests. */
    protected open fun stagingRoot(): File = File(context.cacheDir, STAGING_DIR)

    private fun stagingFileFor(id: Long): File =
        File(stagingRoot().apply { mkdirs() }, "narcictub_${id}.part")

    companion object {
        private const val STAGING_DIR = "downloads"
    }
}

/** App-lifetime scope for download orchestration (provided in DataModule). */
fun downloadWorkScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
