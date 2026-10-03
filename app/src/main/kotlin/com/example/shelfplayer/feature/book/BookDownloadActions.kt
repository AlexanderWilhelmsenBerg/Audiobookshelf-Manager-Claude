package com.example.shelfplayer.feature.book

import com.example.shelfplayer.domain.download.DownloadExecutionObserver
import com.example.shelfplayer.domain.usecase.DownloadBookUseCase
import com.example.shelfplayer.domain.usecase.PauseDownloadUseCase
import javax.inject.Inject

/**
 * PRODUCT_SPEC DL-001 / #108 / #109 / #111 — what the Book screen's download button needs beyond the manifest.
 *
 * Bundled so `BookViewModel` stays under detekt's constructor limit.
 *
 * @property downloadBook starts, joins, resumes and retries. It re-checks the grant and the free space.
 * @property pauseDownload the prompt's Pause. It writes the durable Paused intent first and then cancels
 *   the work, so a paused book can never be left reading as downloading.
 * @property execution transient WorkManager evidence. It refines the manifest (a Failed manifest that is
 *   being retried is *downloading*) and supplies live progress; it is never durable truth.
 */
class BookDownloadActions @Inject constructor(
    val downloadBook: DownloadBookUseCase,
    val pauseDownload: PauseDownloadUseCase,
    val execution: DownloadExecutionObserver,
)
