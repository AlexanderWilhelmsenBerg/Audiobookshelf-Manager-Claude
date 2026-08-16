package com.example.shelfplayer.feature.book

import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.domain.usecase.RemoveDownloadUseCase
import com.example.shelfplayer.domain.usecase.RemoveFromDatabaseUseCase
import javax.inject.Inject

/**
 * PRODUCT_SPEC DL-003 / MGR-005 — the two ways a book can be removed, and they are not the same thing.
 *
 * [local] deletes this device's copy and touches no server. [fromServer] removes the item from the
 * Audiobookshelf **database**, leaves every media file where it is, and offers the local delete as a
 * separate, unchecked choice.
 *
 * Bundled because they arrive together and are read together, and because `BookViewModel` reached eleven
 * constructor parameters when the second one landed — which is the point at which the argument list stops
 * being readable and somebody passes them in the wrong order. Two use cases in one holder that names what
 * they have in common is better than two more parameters that do not.
 */
class BookRemovals @Inject constructor(
    val local: RemoveDownloadUseCase,
    val fromServer: RemoveFromDatabaseUseCase,
    /**
     * PRODUCT_SPEC MGR-005 — "offline invocation is blocked", which makes connectivity part of *whether a
     * removal is offered* rather than a separate concern the screen happens to also need.
     *
     * It lives here for that reason and not for arithmetic: this type answers "what can be removed, and may
     * it be", and the second half of that question is unanswerable without knowing if the server is
     * reachable.
     */
    val network: NetworkMonitor,
)
