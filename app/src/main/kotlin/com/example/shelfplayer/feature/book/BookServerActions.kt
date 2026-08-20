package com.example.shelfplayer.feature.book

import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.domain.usecase.EmbedMetadataUseCase
import com.example.shelfplayer.domain.usecase.EmbedTaskWatcher
import com.example.shelfplayer.domain.usecase.RemoveDownloadUseCase
import com.example.shelfplayer.domain.usecase.RemoveFromDatabaseUseCase
import javax.inject.Inject

/**
 * PRODUCT_SPEC DL-003 / MGR-005 / MGR-007 — the privileged things this screen can ask for.
 *
 * Everything here changes a server or a device rather than a view, and everything here is gated on a
 * permission. That is what they have in common and why they travel together.
 *
 * Bundled because `BookViewModel` reached detekt's constructor limit when the second removal landed — the
 * point at which an argument list stops being readable and somebody passes two use cases in the wrong
 * order. It was called `BookRemovals` until the embed arrived and made the name a lie.
 *
 * @property removeDownload deletes this device's copy and touches no server.
 * @property removeFromServer removes the item from the Audiobookshelf **database**, leaves every media file
 *   where it is, and offers the local delete as a separate, unchecked choice.
 * @property network PRODUCT_SPEC MGR-005 — "offline invocation is blocked", which makes connectivity part
 *   of *whether a removal is offered* rather than a separate concern the screen happens to also need. It
 *   lives here for that reason and not for arithmetic: this type answers "what can be done to this book,
 *   and may it be", and the second half is unanswerable without knowing if the server is reachable.
 * @property embedMetadata PRODUCT_SPEC MGR-007 — asks the server to rewrite the item's own audio files.
 * @property embedTasks the websocket half of the same operation. Separate from [embedMetadata] because they
 *   are genuinely two halves: one sends a request that returns immediately, and the other listens for the
 *   outcome, which may arrive minutes later or not at all.
 */
class BookServerActions @Inject constructor(
    val removeDownload: RemoveDownloadUseCase,
    val removeFromServer: RemoveFromDatabaseUseCase,
    val network: NetworkMonitor,
    val embedMetadata: EmbedMetadataUseCase,
    val embedTasks: EmbedTaskWatcher,
)
