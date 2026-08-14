package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.download.OfflineFile

/**
 * PRODUCT_SPEC DL-001 — what a book is made of, as the downloader needs to see it.
 *
 * ### Why this is a seam rather than a call into the library repository
 *
 * The catalogue lives in `:data:library` and the manifest lives in `:data:downloads`, and neither should
 * import the other. This is the one fact that has to cross: *which files does this book have, in what order,
 * how big, and of what type.* Expressing it as an interface in `:domain` keeps the modules apart and puts
 * the answer in the module that owns the tracks.
 *
 * It is deliberately not `LibraryRepository.observeTracks`. The download path wants a snapshot, once, of the
 * files it is about to fetch — a `Flow` would invite a downloader that re-plans mid-transfer, and a sync
 * arriving halfway through a book is not a reason to change what is being downloaded.
 */
interface BookAssetSource {

    /**
     * The files [bookId] needs to be playable offline, in playback order.
     *
     * Excluded tracks are **not** included. PLAY-003 says an excluded track is not played, so downloading
     * one would spend a listener's data on bytes nothing will ever open — and, worse, would make a book
     * appear incompletely downloaded forever if the exclusion changed the count.
     *
     * Fails when the book is not in the catalogue, or when this profile may not see it (PRODUCT_SPEC 5.2).
     */
    suspend fun assetsFor(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookAssets>
}

/**
 * @property files every audio file, in playback order, described but not yet fetched. Their
 *   [OfflineFile.uri] is empty: where a file will live is the storage layer's decision, not the catalogue's.
 * @property coverUrl the artwork to fetch alongside, or `null`. An absent cover never makes a download
 *   incomplete — a book with every audio file is completely listenable.
 * @property estimatedBytes what the catalogue believes the whole book weighs, for DL-001's free-space check.
 *   An estimate rather than a promise: it is the sum of the sizes the *scan* recorded, and the server is
 *   entitled to send something else.
 */
data class BookAssets(val files: List<OfflineFile>, val coverUrl: String?, val estimatedBytes: Long)
