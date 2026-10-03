package com.example.shelfplayer.feature.downloads

import com.example.shelfplayer.domain.usecase.DownloadBookUseCase
import com.example.shelfplayer.domain.usecase.PauseDownloadUseCase
import com.example.shelfplayer.domain.usecase.RemoveDownloadUseCase
import javax.inject.Inject

/**
 * BW-DL-03 / #108 / #111 — what a Downloads row can ask for.
 *
 * Bundled so `DownloadsViewModel` stays under detekt's constructor limit and so the three actions, which
 * must agree with each other about the active profile's claim, travel together.
 *
 * @property pause stops a transfer without discarding what it fetched.
 * @property download the resume/retry half. It re-checks the grant and the free space, which a bare
 *   re-enqueue would not.
 * @property remove releases this profile's claim, cancelling the transfer first when nobody else claims it.
 */
class DownloadRowActions @Inject constructor(
    val pause: PauseDownloadUseCase,
    val download: DownloadBookUseCase,
    val remove: RemoveDownloadUseCase,
)
