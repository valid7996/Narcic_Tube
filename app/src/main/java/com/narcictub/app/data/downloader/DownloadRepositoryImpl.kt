package com.narcictub.app.data.downloader

import android.content.Context
import android.net.Uri
import com.narcictub.app.data.local.MediaFileChecker
import com.narcictub.app.data.local.MediaStoreFileWriter
import com.narcictub.app.data.ytdlp.YtDlpFileDownloader
import com.narcictub.app.data.ytdlp.YtDlpMime
import com.narcictub.app.domain.downloader.DownloadException
import com.narcictub.app.domain.downloader.FileDownloader
import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.model.MediaFileAvailability
import com.narcictub.app.domain.model.OpenableMedia
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
 * after that full commit. The whole commit phase runs under NonCancellable
 * (L-1): a user cancel that lands mid-commit is "too late" — the commit is
 * short local I/O and is allowed to finish, so no half-committed row can
 * exist (localUri set but status stuck DOWNLOADING, or CANCELLED with a
 * published file on disk). Any failure BEFORE the commit deletes the
 * staging file and marks the row FAILED (or CANCELLED on user cancel);
 * the cancellation handler also refuses to downgrade a row that already
 * reached COMPLETED.
 */
@Singleton
open class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: FileDownloader,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val mediaStoreWriter: MediaStoreFileWriter,
    private val mediaFileChecker: MediaFileChecker,
    private val workScope: CoroutineScope,
) : DownloadRepository {

    private val activeJobs = mutableMapOf<Long, Job>()
    private val waitingIds = ArrayDeque<Long>()

    /** Rows currently being re-queued by retry() — duplicate-tap guard. */
    private val retryingIds = mutableSetOf<Long>()

    /**
     * Ids whose in-flight job.cancel() was initiated by [pause], not
     * [cancel]. Consumed (removed) by the job's own cancellation handler,
     * which is the only place that can tell PAUSED and CANCELLED apart —
     * both start as the exact same coroutine cancellation.
     */
    private val pausingIds = mutableSetOf<Long>()

    /**
     * Recovery boundary (Phase 7 fix round, Qwen finding 3): wall clock
     * captured once at construction — effectively process start, since this
     * singleton is created during Application.onCreate before any UI exists.
     * Every row is created through [enqueue] on this instance, so any row
     * with createdAt ≥ this boundary belongs to THIS process and is never
     * treated as interrupted. Residual, documented: a wall-clock step
     * backwards between construction and a later enqueue could misjudge
     * that one row; the failure mode is a mislabeled-but-retryable row,
     * not data corruption.
     */
    private val processStartMillis: Long = System.currentTimeMillis()

    private var semaphore = Semaphore(permits = 1)
    private var semaphoreCapacity = 1

    private val _progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    override val progress: StateFlow<Map<Long, DownloadProgress>> = _progress.asStateFlow()

    override fun observeDownloads(): Flow<List<HistoryItem>> =
        historyRepository.observeHistory()

    override suspend fun enqueue(sourceUrl: String, durationSeconds: Long?): Long =
        enqueueTitled(sourceUrl, durationSeconds, title = null)

    override suspend fun enqueueTitled(
        sourceUrl: String,
        durationSeconds: Long?,
        title: String?,
        mimeType: String?,
    ): Long {
        val id = historyRepository.add(
            HistoryItem(
                sourceUrl = sourceUrl,
                // A real resolver title (YouTube/Instagram) wins; direct links
                // keep the URL-derived name exactly as before.
                title = displayTitleFor(title) ?: FileNameSanitizer.fromUrl(sourceUrl) ?: "download",
                // Early classification hint (from the resolved variant) so the
                // finished file can be routed to Movies/Music even if the
                // live download response doesn't repeat a usable Content-Type.
                // The real, server-observed type still overwrites this at
                // commit time (see the DOWNLOADING→COMPLETED commit below).
                mimeType = mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                status = DownloadStatus.QUEUED,
                createdAt = Instant.now(),
                // PHASE 22: the real resolved duration travels with the row.
                durationSeconds = durationSeconds,
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
            // Race fallback / PAUSED: no live job and not in the wait queue —
            // covers a still-QUEUED row from a race, and a PAUSED row (which
            // by definition has no active job to cancel). Either way, any
            // partial bytes left on disk from an in-flight or paused attempt
            // are cleaned up — a genuine cancel never resumes.
            val item = runCatching { historyRepository.get(id) }.getOrNull()
            if (item != null && (item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.PAUSED)) {
                historyRepository.updateStatus(id, DownloadStatus.CANCELLED)
                _progress.update { it - id }
                cleanupStagingFor(id)
            }
        }
    }

    /**
     * Pauses a QUEUED or DOWNLOADING item. Cancelling its job is the same
     * mechanism [cancel] uses — [pausingIds] is how the job's own
     * cancellation handler (in [pumpQueue]/[runDownload]) tells a pause
     * apart from a real cancel, so it keeps the partial staging file
     * instead of deleting it.
     */
    override suspend fun pause(id: Long) {
        val item = historyRepository.get(id) ?: return
        if (item.status != DownloadStatus.DOWNLOADING && item.status != DownloadStatus.QUEUED) return
        val job = synchronized(this) { activeJobs[id] }
        if (job != null) {
            synchronized(this) { pausingIds.add(id) }
            job.cancel()
            return
        }
        // Still waiting for a permit, no job launched yet: park it directly
        // — there are no partial bytes to preserve.
        val removed = synchronized(this) { waitingIds.remove(id) }
        if (removed) {
            historyRepository.updateStatus(id, DownloadStatus.PAUSED)
        }
    }

    /**
     * Resumes a PAUSED item by simply re-queuing it: [runDownload] itself
     * detects and continues from any bytes [pause] left on the staging
     * file (see there) — no special "resume" code path is needed here.
     */
    override suspend fun resume(id: Long) {
        val item = historyRepository.get(id) ?: return
        if (item.status != DownloadStatus.PAUSED) return
        historyRepository.updateStatus(id, DownloadStatus.QUEUED)
        synchronized(this) { waitingIds.addLast(id) }
        workScope.launch { pumpQueue() }
    }

    override suspend fun retry(id: Long): Long? {
        // Failed/cancelled rows only; anything else is not retryable.
        val item = historyRepository.get(id) ?: return null
        if (item.status != DownloadStatus.FAILED && item.status != DownloadStatus.CANCELLED) return null

        // Duplicate-tap protection: remember rows already re-queued by a
        // retry so a double tap cannot insert two jobs for one row. The
        // second tap finds the row deleted and stops here.
        synchronized(this) {
            if (id in retryingIds) return null
            retryingIds.add(id)
        }
        try {
            val removed = deleteRowIfStatusMatches(id, setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED))
            if (!removed) return null
            // PHASE 22: retry preserves the real duration the row carried.
            // Provider rows keep their real title; direct-link rows (whose title
            // is just the URL's file name) re-derive it exactly as before.
            val keptTitle = item.title.takeIf { it != FileNameSanitizer.fromUrl(item.sourceUrl) }
            return enqueueTitled(item.sourceUrl, item.durationSeconds, keptTitle)
        } finally {
            synchronized(this) { retryingIds.remove(id) }
        }
    }

    override suspend fun remove(id: Long): Boolean {
        val item = historyRepository.get(id) ?: return false
        return when (item.status) {
            DownloadStatus.COMPLETED -> {
                // Best effort: delete the published file; a missing file
                // (already removed via system UI) still removes the record.
                item.localUri?.let { uriText ->
                    runCatching { mediaStoreWriter.delete(Uri.parse(uriText)) }
                }
                historyRepository.delete(id)
                true
            }
            // Terminal-but-unfinished rows are records only — no file exists.
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                historyRepository.delete(id)
                true
            }
            // In-flight rows must be cancelled first — never silently kill
            // an active transfer from a list-item delete.
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED -> false
        }
    }

    override suspend fun removeCompleted(): Int {
        val completed = currentList().filter { it.status == DownloadStatus.COMPLETED }
        for (item in completed) {
            item.localUri?.let { uriText ->
                runCatching { mediaStoreWriter.delete(Uri.parse(uriText)) }
            }
            historyRepository.delete(item.id)
        }
        return completed.size
    }

    override suspend fun removeFailed(): Int {
        // Failed/cancelled rows have no published file (the commit deletes
        // staging on failure); a single bulk delete per status set is exact.
        val failed = currentList().filter {
            it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED
        }
        if (failed.isEmpty()) return 0
        historyRepository.deleteByStatuses(setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED))
        return failed.size
    }

    override suspend fun recoverInterrupted(): Int {
        // Rows stuck in a non-terminal state are leftovers of a previous
        // process (no jobs survive it). Mark them FAILED with a safe,
        // user-readable reason; drop any orphaned staging file. The user
        // retries explicitly — nothing restarts implicitly.
        val interrupted = currentList().filter {
            (it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.PAUSED) &&
                // Fix 3 race guard: only rows that predate this process are
                // orphans. A row enqueued since startup has a live job.
                it.createdAt.toEpochMilli() < processStartMillis
        }
        for (item in interrupted) {
            historyRepository.updateStatus(
                item.id,
                DownloadStatus.FAILED,
                errorMessage = RECOVERED_MESSAGE,
            )
            cleanupStagingFor(item.id)
            _progress.update { it - item.id }
        }
        return interrupted.size
    }

    // ===== PHASE 10: history / local-media management =====

    override suspend fun mediaAvailability(id: Long): MediaFileAvailability {
        val item = historyRepository.get(id)
            ?: return MediaFileAvailability.NOT_APPLICABLE
        val uri = item.localUri
        if (item.status != DownloadStatus.COMPLETED || uri == null) {
            return MediaFileAvailability.NOT_APPLICABLE
        }
        // The checker validates the URI (content:// or app-contained file://)
        // before any resolution — unvalidated URIs never touch a provider
        // or the filesystem.
        return if (mediaFileChecker.isAvailable(uri)) {
            MediaFileAvailability.AVAILABLE
        } else {
            MediaFileAvailability.UNAVAILABLE
        }
    }

    override suspend fun openableMedia(id: Long): OpenableMedia? {
        val item = historyRepository.get(id) ?: return null
        if (item.status != DownloadStatus.COMPLETED) return null
        val uri = item.localUri ?: return null
        if (!mediaFileChecker.isAvailable(uri)) return null
        return OpenableMedia(uriText = uri, mimeType = item.mimeType, title = item.title)
    }

    override suspend fun removeRecord(id: Long): Boolean {
        val item = historyRepository.get(id) ?: return false
        return when (item.status) {
            // Record-only removal: the published file (if any) is left in
            // place — "remove from history" never deletes user media.
            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELLED,
            -> {
                historyRepository.delete(id)
                true
            }
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED,
            -> false
        }
    }

    /**
     * Deletes [id] only if its current status is one of [statuses] — the
     * check-then-delete seam used by retry/remove so a status change
     * between read and delete cannot resurrect or duplicate a row.
     */
    private suspend fun deleteRowIfStatusMatches(id: Long, statuses: Set<DownloadStatus>): Boolean {
        val current = historyRepository.get(id) ?: return false
        if (current.status !in statuses) return false
        historyRepository.delete(id)
        return true
    }

    private suspend fun currentList(): List<HistoryItem> =
        historyRepository.observeHistory().first()

    /**
     * Drains [waitingIds]: launches one child Job per queued id; each Job
     * acquires the concurrency permit before any work. Cancellation before
     * the permit is granted is caught here so the row never stays QUEUED.
     * The CANCELLED write is guarded (L-1): a cancel that lands mid-commit
     * does NOT throw into this catch (the NonCancellable commit finishes
     * and the job simply ends cancelled), but a cancel delivered at any
     * real suspension point is — and must not downgrade a row that has
     * meanwhile reached a terminal state. [markCancelledIfInFlight] handles
     * both.
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
                    // Only two ways to land here: (a) cancelled while still
                    // waiting for a permit — runDownload never started, no
                    // staging file exists yet, this IS the authoritative
                    // handler; or (b) runDownload's own catch already
                    // handled a mid-transfer pause/cancel and re-threw —
                    // pausingIds is already consumed there, and
                    // markPausedIfInFlight/markCancelledIfInFlight's status
                    // guard makes this a safe no-op (also true after a
                    // finished commit, per the L-1 guard below).
                    withContext(NonCancellable) {
                        if (synchronized(this@DownloadRepositoryImpl) { pausingIds.remove(id) }) {
                            markPausedIfInFlight(id)
                        } else {
                            markCancelledIfInFlight(id)
                        }
                    }
                    _progress.update { it - id }
                }
            }
            synchronized(this) { activeJobs[id] = job }
        }
    }

    /**
     * L-1: records CANCELLED only while the row is still in flight
     * (QUEUED/DOWNLOADING). Rows that already reached a terminal state —
     * most importantly COMPLETED, written by the NonCancellable commit —
     * are never downgraded by a late-arriving cancellation.
     */
    private suspend fun markCancelledIfInFlight(id: Long) {
        val status = runCatching { historyRepository.get(id)?.status }.getOrNull()
        if (status == null || status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING) {
            runCatching { historyRepository.updateStatus(id, DownloadStatus.CANCELLED) }
        }
    }

    /** Same guard as [markCancelledIfInFlight], writing PAUSED instead. */
    private suspend fun markPausedIfInFlight(id: Long) {
        val status = runCatching { historyRepository.get(id)?.status }.getOrNull()
        if (status == null || status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING) {
            runCatching { historyRepository.updateStatus(id, DownloadStatus.PAUSED) }
        }
    }

    /** Deletes a row's staging file AND any yt-dlp working directory left beside it. */
    private fun cleanupStagingFor(id: Long) {
        val stagingFile = stagingFileFor(id)
        runCatching { stagingFile.delete() }
        runCatching { YtDlpFileDownloader.workDirFor(stagingFile).deleteRecursively() }
    }

    private suspend fun runDownload(id: Long) {
        val item = historyRepository.get(id) ?: return
        if (item.status != DownloadStatus.QUEUED) return

        historyRepository.updateStatus(id, DownloadStatus.DOWNLOADING)

        val stagingFile = stagingFileFor(id)
        // A prior PAUSED attempt at this same id deliberately kept whatever
        // bytes had already landed on disk (see pause()) — continue from
        // there. A brand-new row simply has no staging file yet (0).
        val resumeFromBytes = stagingFile.length()
        _progress.update {
            it + (id to DownloadProgress(downloadedBytes = resumeFromBytes, totalBytes = null))
        }

        try {
            val result = downloader.download(item.sourceUrl, stagingFile, resumeFromBytes) { p ->
                _progress.update { it + (id to p) }
            }

            // Commit phase: publish to MediaStore BEFORE marking complete.
            //
            // L-1 (cancellation race): the ENTIRE commit — publish, localUri,
            // size and the COMPLETED status write — runs under NonCancellable.
            // A cancel() that lands mid-commit used to abort between these
            // writes, leaving a half-committed row (e.g. localUri set but the
            // row stuck in DOWNLOADING, or CANCELLED while the published file
            // actually exists). The commit is short local I/O copying an
            // already-complete staging file, so once it starts it is allowed
            // to finish: a cancel arriving here is simply "too late" and the
            // row lands COMPLETED. Cancellation during the network stream
            // (before this block) still yields CANCELLED + staging cleanup.
            withContext(NonCancellable) {
                // yt-dlp decides the container, so its extension is appended to
                // the (extension-less) title; plain downloads are unchanged.
                val displayName = withExtension(item.fileName ?: item.title, result.fileExtension)
                // PHASE 10 contract, unchanged: what's STORED/DISPLAYED as the
                // history record's type is a real, observed signal only —
                // the live response when it's there, else what the resolver
                // already identified at enqueue time (item.mimeType) — NEVER
                // a guess from the file extension.
                val observedMimeType = result.contentType
                    ?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                    ?: item.mimeType
                // Movies/Music ROUTING can afford to be best-effort where the
                // history record can't: a wrong guess only lands the file in
                // the wrong folder, never puts a false claim in front of the
                // user. So routing alone also tries the real file extension.
                val routingMimeHint = observedMimeType
                    ?: YtDlpMime.forDownloadedFile(File(displayName).extension)
                val published = mediaStoreWriter.publish(
                    stagingFile = stagingFile,
                    displayName = displayName,
                    mimeType = routingMimeHint,
                )

                historyRepository.updateLocalUri(id, published.toString())
                historyRepository.updateSizeBytes(id, result.bytesDownloaded)
                // PHASE 10: persist the real (observed-only) type so history
                // shows the actual format — see observedMimeType above.
                historyRepository.updateMimeType(id, observedMimeType)
                historyRepository.updateStatus(
                    id,
                    DownloadStatus.COMPLETED,
                    completedAt = Instant.now(),
                )
                // Staging file removed only after full commit — also under
                // NonCancellable so cleanup cannot be skipped by a late cancel.
                stagingFile.delete()
            }
            _progress.update { it - id }
        } catch (e: CancellationException) {
            // This is where a pause landing MID-TRANSFER is actually caught
            // (runDownload's own try, not pumpQueue's outer one — that one
            // only ever sees a cancel that happened before this point was
            // reached, e.g. still waiting for a permit). Consuming
            // pausingIds HERE, first, is what makes pumpQueue's later
            // (outer) catch a safe no-op afterwards — see its comment.
            withContext(NonCancellable) {
                if (synchronized(this@DownloadRepositoryImpl) { pausingIds.remove(id) }) {
                    markPausedIfInFlight(id)
                    // Keep the partial staging file on disk — resume()
                    // continues the same transfer instead of restarting it.
                } else {
                    markCancelledIfInFlight(id)
                    // Idempotent: the commit path already deleted it on success.
                    runCatching { stagingFile.delete() }
                }
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

    /**
     * Turns a resolver title into a safe, length-capped display title. Path
     * separators become dashes (a title like "AC/DC live" must not lose its
     * head); null when nothing usable remains. The cap leaves room for the
     * extension so the 120-char file-name limit can never cut it off.
     */
    private fun displayTitleFor(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace('/', '-').replace('\\', '-')
        return FileNameSanitizer.sanitize(cleaned, fallback = "")
            .take(MAX_TITLE_LENGTH)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun withExtension(name: String, extension: String?): String {
        if (extension.isNullOrBlank()) return name
        return if (name.endsWith(".$extension", ignoreCase = true)) name else "$name.$extension"
    }

    /** Root of the shared staging directory. Overridable for tests. */
    protected open fun stagingRoot(): File = File(context.cacheDir, STAGING_DIR)

    private fun stagingFileFor(id: Long): File =
        File(stagingRoot().apply { mkdirs() }, "narcictub_${id}.part")

    companion object {
        private const val STAGING_DIR = "downloads"
        private const val MAX_TITLE_LENGTH = 100
        const val RECOVERED_MESSAGE = "Interrupted by app restart"
    }
}

/** App-lifetime scope for download orchestration (provided in DataModule). */
fun downloadWorkScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
