package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.model.DownloadsOverview
import com.narcictub.app.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** Streams the merged download list + live progress to the Downloads UI. */
class ObserveDownloadsUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    operator fun invoke(): Flow<DownloadsOverview> =
        repository.observeDownloads().combine(repository.progress) { items, progress ->
            DownloadsOverview(items = items, progress = progress)
        }
}
