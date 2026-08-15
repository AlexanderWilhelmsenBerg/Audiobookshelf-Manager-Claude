package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.LibraryItemId

/**
 * PRODUCT_SPEC DL-005 — the halfway trigger, as the progress journal can call it.
 *
 * A seam rather than a direct call to `SmartDownloadUseCase`, for one reason: the use case needs
 * `LibraryRepository` and `DownloadBookUseCase`, and the journal lives in `:data:library`, which
 * `DownloadBookUseCase` transitively depends on. Injecting the use case there would be a cycle.
 *
 * The default implementation does nothing, so a graph that has not bound one — a test, a future variant
 * with the feature compiled out — behaves exactly as it did before smart download existed.
 */
fun interface SmartDownload {

    /**
     * Considers a position that has just been journaled.
     *
     * Must be cheap when the feature is off: this runs every few seconds for the whole length of every
     * book anybody plays.
     */
    suspend operator fun invoke(bookId: LibraryItemId, previousPosition: Long, position: Long, duration: Long)

    companion object {
        /** What a graph with no smart download bound uses. */
        val Disabled: SmartDownload = SmartDownload { _, _, _, _ -> }
    }
}
