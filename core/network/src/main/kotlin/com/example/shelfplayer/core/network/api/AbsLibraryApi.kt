package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.BookSnapshot
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibrarySnapshot
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.network.gateway.CachedLibrary
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import com.example.shelfplayer.core.network.http.RetryPolicy
import retrofit2.Response
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [LibraryApi], against contracts verified on Audiobookshelf 2.36.0.
 *
 * ### Two properties this class is responsible for
 *
 * **An unauthorized library is never returned.** PRODUCT_SPEC 5.2 and the Phase 1 exit criterion require
 * that such a library is not written to Room *at all*, not merely hidden by the UI. Filtering here rather
 * than in the repository is what makes that true by construction: the repository never learns the library
 * exists, so no later code path can reintroduce it.
 *
 * **A partial library is never presented as complete.** The item list is minified — counts, not tracks —
 * so each item needs its own expanded fetch. If one of those fails for a reason other than "the item is
 * gone", the whole library's sync fails. Returning what was fetched would let
 * `markMissingBooksDeleted` soft-delete the books whose fetch failed, which is a transient network error
 * turning into a user's downloaded book disappearing (product priority 2).
 */
@Singleton
internal class AbsLibraryApi @Inject constructor(
    private val services: AudiobookshelfServiceFactory,
    private val connections: ProfileConnectionResolver,
    private val errors: NetworkErrorMapper,
    private val retries: RetryPolicy,
    private val clock: AppClock,
    private val logger: Logger,
) : LibraryApi {

    override suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>> =
        withConnection(profileId) { connection, service ->
            retries.readOnly("listLibraries") {
                service.libraries(bearerOf(connection.accessToken.value)).toResult { body ->
                    LibraryMapper.toLibraries(connection.serverId, body ?: LibrariesResponseDto(), clock.now())
                }
            }.map { libraries -> accessible(connection, libraries) }
        }

    /**
     * PRODUCT_SPEC 5.2 — the grant is applied before anything leaves this class.
     *
     * The count of what was dropped is logged, not the names: a library name is private self-hosted data
     * (PRODUCT_SPEC 14.5), and a count is enough to tell "this account sees two of five libraries" from
     * "this server has two libraries".
     */
    private fun accessible(connection: ProfileConnection, libraries: List<Library>): List<Library> {
        val permitted = libraries.filter { library -> connection.access.allows(library.id) }
        if (permitted.size != libraries.size) {
            logger.info(
                LogCategory.Sync,
                "Filtered libraries this profile is not granted",
                LogField.Identifier("profile", connection.profileId.value),
                LogField.Count("granted", permitted.size),
                LogField.Count("withheld", libraries.size - permitted.size),
            )
        }
        return permitted
    }

    /**
     * PRODUCT_SPEC LIB-001 — the catalogue first, then one expanded fetch per item.
     *
     * It is still N+1 requests, and it has to be: the list endpoint sends `numTracks` rather than the
     * tracks, and PRODUCT_SPEC 2.3 needs track offsets stored beside the book or offline resume cannot
     * work. What changed is that the N is no longer on the critical path.
     *
     * The catalogue response is handed to `onCatalogueBatch` **before** any item is expanded, so the
     * shelf is populated after **one** round trip instead of after 491 on the library a device run used.
     * The expansion pass then hands complete books to `onBatch`, adding their tracks, chapters, authors
     * and series.
     *
     * The sinks are deliberately separate. A catalogue row is a browsable preview missing exactly the
     * fields the list endpoint does not carry; persisting it through the complete-book sink would erase
     * an unchanged cached book's relationships. See `LibraryMapper.toCatalogueSnapshots` for the gap.
     */
    override suspend fun listBooks(
        profileId: ProfileId,
        libraryId: LibraryId,
        onBatch: suspend (List<BookSnapshot>) -> Unit,
        cached: CachedLibrary,
        onCatalogueBatch: suspend (List<BookSnapshot>) -> Unit,
    ): AppResult<LibrarySnapshot> = withConnection(profileId) { connection, service ->
        // Checked again rather than trusted from listLibraries: a caller can ask for any library id,
        // including one it never saw in a listing (a deep link, a stale cached id). PRODUCT_SPEC 15
        // requires deep links to validate access, and this is where that validation has to hold.
        if (!connection.access.allows(libraryId)) {
            return@withConnection AppResult.Failure(
                AppError.Authorization(
                    summary = "This account is not allowed to see that library.",
                    missingPermission = "library.read",
                ),
            )
        }
        val bearer = bearerOf(connection.accessToken.value)
        val fetchedAt = clock.now()
        val target = Target(connection, libraryId, service, bearer)
        when (val catalogue = catalogue(target, fetchedAt, onCatalogueBatch)) {
            is AppResult.Failure -> catalogue
            is AppResult.Success -> {
                logger.info(
                    LogCategory.Sync,
                    "Catalogue written; expanding items",
                    LogField.Count("browsable", catalogue.value.browsable),
                    LogField.Count("toExpand", catalogue.value.ids.size),
                )
                snapshots(
                    target = target,
                    catalogue = catalogue.value,
                    onBatch = onBatch,
                    fetchedAt = fetchedAt,
                    cached = cached,
                )
            }
        }
    }

    /**
     * PRODUCT_SPEC LIB-002 — the server's answer, for what the cache could not answer.
     *
     * Access is re-checked here for the same reason [listBooks] re-checks it: a library id reaching this
     * method has not necessarily come from a listing this profile was shown.
     */
    override suspend fun searchBooks(
        profileId: ProfileId,
        libraryId: LibraryId,
        query: String,
    ): AppResult<List<BookSnapshot>> = withConnection(profileId) { connection, service ->
        if (!connection.access.allows(libraryId)) {
            return@withConnection AppResult.Failure(
                AppError.Authorization(
                    summary = "This account is not allowed to see that library.",
                    missingPermission = "library.read",
                ),
            )
        }
        val fetchedAt = clock.now()
        retries.readOnly("searchLibrary") {
            service
                .search(bearerOf(connection.accessToken.value), libraryId.value, query, SEARCH_LIMIT)
                .toResult { body ->
                    AppResult.Success(
                        LibraryMapper.toSearchSnapshots(
                            serverId = connection.serverId,
                            libraryId = libraryId,
                            profileId = connection.profileId,
                            dto = body ?: LibrarySearchResponseDto(),
                            fetchedAt = fetchedAt,
                        ),
                    )
                }
        }
    }

    /**
     * PRODUCT_SPEC MGR-001 / MGR-004 — one item, expanded, after a management operation changed it.
     *
     * The authorization check is the same one the catalogue sync makes and is not a formality here: an
     * item id is a guessable string, so a profile that has lost access to a library must not be able to
     * refresh a book out of it by asking for the book directly (PRODUCT_SPEC 5.2). The library comes from
     * the response, so the check happens after the fetch and before anything is returned.
     */
    override suspend fun fetchBook(profileId: ProfileId, bookId: LibraryItemId): AppResult<BookSnapshot> =
        withConnection(profileId) { connection, service ->
            val fetchedAt = clock.now()
            retries.readOnly("fetchBook") {
                service
                    .item(bearerOf(connection.accessToken.value), bookId.value)
                    .toResult { body -> snapshotOf(connection, body, fetchedAt) }
            }
        }

    private fun snapshotOf(
        connection: ProfileConnection,
        dto: LibraryItemDto?,
        fetchedAt: Instant,
    ): AppResult<BookSnapshot> {
        val libraryId = dto?.libraryId?.let(::LibraryId)
            ?: return AppResult.Failure(
                AppError.ApiCompatibility(
                    summary = "The server described that book without saying which library it is in.",
                    missingField = "libraryId",
                ),
            )
        if (!connection.access.allows(libraryId)) {
            return AppResult.Failure(
                AppError.Authorization(
                    summary = "This account is not allowed to see that library.",
                    missingPermission = "library.read",
                ),
            )
        }
        return LibraryMapper.toSnapshot(
            serverId = connection.serverId,
            libraryId = libraryId,
            profileId = connection.profileId,
            dto = dto,
            fetchedAt = fetchedAt,
        )
    }

    /**
     * D1 — the catalogue in pages, each one handed over as it lands.
     *
     * The capture was taken with no page size and the server answered `limit: 0` with the whole library
     * in one body. That is a default, not a contract, and on a five-thousand-item library it is a single
     * response measured in megabytes that nothing can render until the last byte of it arrives. Asking
     * for [PAGE_SIZE] at a time means the first shelf is drawn after the first hundred books.
     *
     * Two completion bounds and one fail-closed consistency check:
     *
     *  - the envelope's `total` has been reached — the normal exit, and the one that also covers a
     *    server that ignores `limit` and sends everything on page zero;
     *  - an empty or repeated page before `total` is reached — an invalid/incomplete listing, never
     *    permission to reconcile deletions;
     *  - [MAX_PAGES] — the backstop for a server that ignores `page` and answers the same rows forever.
     *    Hitting it is logged as a warning, never silently treated as the end of the library, because a
     *    truncated catalogue that read as complete would let reconciliation delete the remainder.
     */
    private suspend fun catalogue(
        target: Target,
        fetchedAt: Instant,
        onCatalogueBatch: suspend (List<BookSnapshot>) -> Unit,
    ): AppResult<Catalogue> {
        val collected = CatalogueAccumulator()
        var page = 0
        while (page < MAX_PAGES) {
            val response = cataloguePage(target, page)
            val body = when (response) {
                is AppResult.Failure -> {
                    // A later page failing is still a failure. Earlier pages are already on screen, but
                    // returning their ids would tell reconciliation every unlisted book had been deleted.
                    return response
                }

                is AppResult.Success -> response.value
            }
            val rows = LibraryMapper.toCatalogueSnapshots(target.connection.serverId, target.libraryId, body, fetchedAt)
            when (val accepted = collected.accept(body, rows.size)) {
                is AppResult.Failure -> return accepted
                is AppResult.Success -> {
                    // Before a single item is expanded. This is the whole of P1-31: one round trip, and
                    // the user has a library — now one round trip regardless of how large that library is.
                    if (rows.isNotEmpty()) onCatalogueBatch(rows)
                    if (accepted.value) return AppResult.Success(collected.snapshot())
                }
            }
            page++
        }
        logger.warn(
            LogCategory.Sync,
            "Stopped paging the catalogue at the page limit; the listing is incomplete",
            LogField.Count("pages", page),
            LogField.Count("received", collected.size),
        )
        return AppResult.Failure(
            catalogueCompatibility("This server did not finish listing the library.", "page"),
        )
    }

    private suspend fun cataloguePage(target: Target, page: Int): AppResult<LibraryItemsResponseDto> =
        retries.readOnly("listItems") {
            target.service.items(target.bearer, target.libraryId.value, PAGE_SIZE, page)
                .toResult(::validateCatalogueBody)
        }

    private fun validateCatalogueBody(body: LibraryItemsResponseDto?): AppResult<LibraryItemsResponseDto> = when {
        body == null -> AppResult.Failure(
            catalogueCompatibility("This server returned an empty catalogue response.", "body"),
        )

        body.results == null -> AppResult.Failure(
            catalogueCompatibility("This server did not include catalogue results.", "results"),
        )

        body.total < 0 -> AppResult.Failure(
            catalogueCompatibility("This server did not report the catalogue total.", "total"),
        )

        else -> AppResult.Success(body)
    }

    private fun catalogueCompatibility(summary: String, missingField: String) =
        AppError.ApiCompatibility(summary = summary, missingField = missingField)

    /**
     * PRODUCT_SPEC LIB-001 — "failed optional sections do not fail the whole sync".
     *
     * This used to `return` on the first item that failed, discarding every snapshot fetched before it. On
     * a 490-book library that is 490 chances for one timeout to throw away the entire sync, and a device
     * run duly produced an empty library that a manual retry then filled. The books that *were* fetched are
     * real; keeping them is strictly better than keeping nothing.
     *
     * The count of unreachable items rides along, because the caller needs it to decide whether it may
     * treat absence as deletion. A fetch where **everything** failed is still reported as a failure — a
     * server that answered nothing must not read as a library with no books in it.
     */
    private suspend fun snapshots(
        target: Target,
        catalogue: Catalogue,
        onBatch: suspend (List<BookSnapshot>) -> Unit,
        fetchedAt: Instant,
        cached: CachedLibrary,
    ): AppResult<LibrarySnapshot> {
        val ids = catalogue.ids
        val sweep = Sweep()
        var emitted = 0
        var skipped = 0
        for (id in expansionOrder(ids, cached)) {
            if (sweep.giveUp) {
                sweep.skip()
            } else if (cached.isUpToDate(id, catalogue.stamps[id])) {
                // PRODUCT_SPEC LIB-001 — the expensive half of a refresh, not done twice.
                //
                // The catalogue reports `updatedAt` per item. A stored book carrying the same stamp and
                // already holding its tracks is, by the server's own account, unchanged — so fetching it
                // again buys nothing and costs a round trip. On a library that has not been edited since
                // the last sync this turns 490 requests into none, which is the whole difference between
                // a refresh that takes minutes and one that returns at once.
                //
                // It is the *server's* timestamp that decides, never a local clock: a device with a
                // wrong date must not be able to convince the app that a changed book is current.
                skipped++
            } else {
                sweep.record(id, fetchItem(target, id, fetchedAt))
            }
            // Handed over in batches rather than per item: one transaction per book on a 490-book
            // library is 490 transactions, and the shelf cannot redraw that fast anyway. Twenty is
            // frequent enough to look continuous and rare enough not to thrash the database.
            if (sweep.books.size - emitted >= BATCH_SIZE) {
                onBatch(sweep.books.subList(emitted, sweep.books.size).toList())
                emitted = sweep.books.size
            }
        }
        if (sweep.books.size > emitted) {
            onBatch(sweep.books.subList(emitted, sweep.books.size).toList())
        }
        if (sweep.giveUp) {
            logger.warn(
                LogCategory.Sync,
                "Stopped fetching items after consecutive failures; the rest are marked unreachable",
                LogField.Count("limit", CONSECUTIVE_FAILURE_LIMIT),
                LogField.Count("unreachable", sweep.unreachable),
            )
        }
        if (skipped > 0) {
            logger.info(
                LogCategory.Sync,
                "Skipped items the server reports unchanged",
                LogField.Count("skipped", skipped),
                LogField.Count("fetched", ids.size - skipped),
            )
        }
        logCounts(sweep.removed, sweep.unreachable, ids.size)
        val failure = sweep.firstFailure
        // Nothing expanded and something went wrong. Still a failure — the caller uses it to decide
        // whether absence may mean deletion, and it must never conclude "deleted" from a dead
        // connection. The user is not left with nothing, though: the catalogue rows are already
        // written and browsable, which is the difference this pass made.
        if (sweep.books.isEmpty() && failure != null && skipped == 0) return AppResult.Failure(failure)
        return AppResult.Success(
            LibrarySnapshot(
                books = sweep.books,
                removedCount = sweep.removed,
                unreachableCount = sweep.unreachable,
                // PRODUCT_SPEC 5.2 — the catalogue, not the successfully-fetched subset. These are the ids
                // the server was willing to list *for this profile*, which is the only place item-level
                // visibility is observable: an account restricted by tag inside a shared library gets a
                // shorter list here and an identical response everywhere else.
                visibleIds = ids,
                removedIds = sweep.removedIds.toList(),
            ),
        )
    }

    /**
     * PRODUCT_SPEC LIB-002 — the *Continue listening* shelf is expanded before the rest of the library.
     *
     * Asked for from a device: the books that were started and not finished are the ones a returning
     * user opens the app for, and on a large library they were arriving whenever the catalogue happened
     * to list them.
     *
     * It is **only a reordering**. The same items are fetched, the same requests are made, and nothing
     * is added or skipped — so a refresh cannot come out slower than it was. What changes is which shelf
     * is complete first.
     *
     * `partition` is stable, so within each half the catalogue's own order survives. That matters for
     * the second half: the server lists items in a deliberate order, and shuffling the remainder to make
     * a point about the first few would be a worse trade than the one being made here.
     *
     * The very first sync of an empty cache has no progress to sort by and gets catalogue order, which
     * is correct — there is no *Continue listening* shelf on an account that has not listened to
     * anything.
     */
    private fun expansionOrder(ids: List<LibraryItemId>, cached: CachedLibrary): List<LibraryItemId> {
        val (started, rest) = ids.partition(cached::isInProgress)
        if (started.isEmpty()) return ids
        logger.info(
            LogCategory.Sync,
            "Expanding in-progress books before the rest of the library",
            LogField.Count("inProgress", started.size),
            LogField.Count("total", ids.size),
        )
        return started + rest
    }

    /**
     * One item, with PRODUCT_SPEC 14.3's retry budget around it.
     *
     * The three outcomes are deliberately distinct rather than "worked or did not": a `404` is a fact
     * about the item that the repository may act on, and a failure is a fact about this attempt that it
     * must not.
     */
    private suspend fun fetchItem(target: Target, id: LibraryItemId, fetchedAt: Instant): ItemOutcome {
        val service = target.service
        val bearer = target.bearer
        val connection = target.connection
        val libraryId = target.libraryId
        val response = retries.readOnly("getItem") {
            val attempt = service.item(bearer, id.value)
            // A 404 leaves the retry loop by counting as a success here: the item is gone, and asking
            // three more times will not bring it back.
            if (attempt.code() == HTTP_NOT_FOUND) AppResult.Success(attempt) else attempt.asRetryable()
        }
        if (response is AppResult.Failure) return ItemOutcome.Unreachable(response.error)

        val body = (response as AppResult.Success).value
        // Removed between the listing and this fetch. Genuinely gone, so omitting it is correct and the
        // repository will soft-delete it — unlike a network failure, which says nothing about whether the
        // item still exists.
        if (body.code() == HTTP_NOT_FOUND) return ItemOutcome.Removed

        val mapped = body.toResult { item ->
            if (item == null) {
                AppResult.Failure(
                    AppError.ApiCompatibility(
                        summary = "This server returned an empty response for a library item.",
                        missingField = "item",
                    ),
                )
            } else {
                LibraryMapper.toSnapshot(connection.serverId, libraryId, connection.profileId, item, fetchedAt)
            }
        }
        return when (mapped) {
            is AppResult.Failure -> ItemOutcome.Unreachable(mapped.error)
            is AppResult.Success -> ItemOutcome.Fetched(mapped.value)
        }
    }

    private sealed interface ItemOutcome {
        data class Fetched(val snapshot: BookSnapshot) : ItemOutcome

        data object Removed : ItemOutcome

        data class Unreachable(val error: AppError) : ItemOutcome
    }

    /**
     * The running tally of one library's sweep, including when to stop.
     *
     * PRODUCT_SPEC 14.3 versus the N+1: a retry budget per item is right for a library where one request
     * in five hundred hiccups, and catastrophic for a server that has fallen over — 490 items times four
     * attempts, each with its own backoff, is a sync that runs for an hour to learn what the first
     * request already said.
     *
     * [giveUp] resolves it. Consecutive failures mean the server, not the item, and the remaining items
     * are counted unreachable — the state that already forbids deletions, so stopping early costs no
     * data and no correctness. Any success resets the count, because a library with a few scattered bad
     * items is a different situation from a library behind a dead server.
     */
    /**
     * Everything an item fetch needs that does not vary per item.
     *
     * The four travelled together through every signature in this file, which is what pushed the sweep
     * past the parameter limit. Bundling them is not only a count fix: they are one thing — the
     * connection this sweep is running over.
     */
    private data class Target(
        val connection: ProfileConnection,
        val libraryId: LibraryId,
        val service: LibraryService,
        val bearer: String,
    )

    /**
     * What every page of the catalogue added up to.
     *
     * [browsable] is a count rather than the rows: each page's rows were handed to `onCatalogueBatch` as it
     * arrived, so holding all of them here would keep a second copy of the whole library in memory for
     * the length of the expansion pass — which is exactly the cost paging was added to avoid.
     */
    private data class Catalogue(
        val ids: List<LibraryItemId>,
        val browsable: Int,
        /** `updatedAt` per item, which is how an unchanged item is recognised without fetching it. */
        val stamps: Map<LibraryItemId, Long?>,
    )

    /**
     * Accumulates validated pages and refuses to call an incomplete or repeated listing complete.
     * Unique ids are the reconciliation evidence; raw row counts can be inflated by a repeated page.
     */
    private inner class CatalogueAccumulator {
        private val ids = linkedSetOf<LibraryItemId>()
        private val stamps = mutableMapOf<LibraryItemId, Long?>()
        private var advertisedTotal: Int? = null
        private var browsable = 0

        val size: Int get() = ids.size

        fun accept(body: LibraryItemsResponseDto, browsableRows: Int): AppResult<Boolean> {
            val expectedTotal = advertisedTotal ?: body.total.also { advertisedTotal = it }
            if (body.total != expectedTotal) {
                return AppResult.Failure(
                    catalogueCompatibility("The catalogue total changed while it was being listed.", "total"),
                )
            }
            val pageIds = when (val mapped = LibraryMapper.toItemIds(body)) {
                is AppResult.Failure -> return mapped
                is AppResult.Success -> mapped.value
            }
            val beforePage = ids.size
            ids.addAll(pageIds)
            body.results.orEmpty().forEach { item ->
                item.id?.takeIf(String::isNotBlank)?.let { stamps[LibraryItemId(it)] = item.updatedAt }
            }
            val error = when {
                ids.size > expectedTotal -> catalogueCompatibility(
                    "The server listed more unique items than its catalogue total.",
                    "total",
                )

                ids.size == expectedTotal -> null
                body.results.isNullOrEmpty() -> catalogueCompatibility(
                    "The server stopped listing items before reaching its catalogue total.",
                    "results",
                )

                ids.size == beforePage -> catalogueCompatibility(
                    "The server repeated a catalogue page without making progress.",
                    "page",
                )

                else -> null
            }
            if (error != null) return AppResult.Failure(error)
            browsable += browsableRows
            return AppResult.Success(ids.size == expectedTotal)
        }

        fun snapshot() = Catalogue(ids = ids.toList(), browsable = browsable, stamps = stamps.toMap())
    }

    private class Sweep {
        val books = mutableListOf<BookSnapshot>()
        val removedIds = linkedSetOf<LibraryItemId>()
        var removed = 0
            private set
        var unreachable = 0
            private set
        var firstFailure: AppError? = null
            private set

        private var consecutiveFailures = 0

        val giveUp: Boolean get() = consecutiveFailures >= CONSECUTIVE_FAILURE_LIMIT

        /** An item never attempted, because the sweep had already given up. */
        fun skip() {
            unreachable++
        }

        fun record(id: LibraryItemId, outcome: ItemOutcome) {
            when (outcome) {
                is ItemOutcome.Fetched -> {
                    books += outcome.snapshot
                    consecutiveFailures = 0
                }

                ItemOutcome.Removed -> {
                    removed++
                    removedIds += id
                    consecutiveFailures = 0
                }

                is ItemOutcome.Unreachable -> {
                    unreachable++
                    consecutiveFailures++
                    if (firstFailure == null) firstFailure = outcome.error
                }
            }
        }
    }

    /** PRODUCT_SPEC 14.5 — counts, never titles or ids. */
    private fun logCounts(removed: Int, unreachable: Int, total: Int) {
        if (removed > 0) {
            logger.info(
                LogCategory.Sync,
                "Some items disappeared between the listing and their fetch",
                LogField.Count("removed", removed),
            )
        }
        if (unreachable > 0) {
            logger.warn(
                LogCategory.Sync,
                "Some items could not be fetched; the rest of the library was kept",
                LogField.Count("unreachable", unreachable),
                LogField.Count("total", total),
            )
        }
    }

    private inline fun <T, R> Response<T>.toResult(onSuccess: (T?) -> AppResult<R>): AppResult<R> = if (isSuccessful) {
        onSuccess(body())
    } else {
        AppResult.Failure(errors.fromStatus(code(), retryAfterSeconds = retryAfterSeconds()))
    }

    private fun Response<*>.retryAfterSeconds(): Long? = headers()["Retry-After"]?.toLongOrNull()

    private inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
        is AppResult.Success -> AppResult.Success(transform(value))
        is AppResult.Failure -> this
    }

    /**
     * Resolves the profile's connection, then runs [block] with it.
     *
     * A profile with no usable connection is an [AppError.Authentication] rather than an empty list: an
     * empty library renders as "you have no books", and the truth is "this profile needs to sign in
     * again" (PRODUCT_SPEC 14.4).
     */
    private suspend fun <R> withConnection(
        profileId: ProfileId,
        block: suspend (ProfileConnection, LibraryService) -> AppResult<R>,
    ): AppResult<R> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())
        val result = resultOf(onError = errors::fromThrowable) {
            block(connection, services.libraryService(connection.serverUrl))
        }
        // `resultOf` wraps the transport; the block already returns a typed result for the HTTP layer.
        return when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }

    /**
     * A transport response as an [AppResult] the retry policy can read, keeping the body for the caller.
     *
     * The existing [toResult] collapses the response into a mapped value, which is the wrong shape here:
     * the retry decision needs the status, and the mapping needs the body, so this keeps the response
     * whole and lets the caller map it once the retries are settled.
     */
    private fun <T> Response<T>.asRetryable(): AppResult<Response<T>> = if (isSuccessful) {
        AppResult.Success(this)
    } else {
        AppResult.Failure(errors.fromStatus(code(), retryAfterSeconds = retryAfterSeconds()))
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404

        /**
         * How many items in a row may fail before the sweep gives up on the library.
         *
         * Five rather than one: a library can genuinely contain a few unreadable items, and abandoning
         * the whole sync over the first is the over-correction this class was already fixed for once.
         * Five in a row is not an item problem.
         */
        const val CONSECUTIVE_FAILURE_LIMIT = 5

        /** How many books accumulate before the caller is handed them. */
        const val BATCH_SIZE = 20

        /**
         * D1 — catalogue rows per request.
         *
         * A hundred is what both reference clients ask for, and the number is a compromise rather than a
         * measurement: small enough that the first page arrives quickly on a phone connection, large
         * enough that a two-thousand-item library is twenty requests rather than two hundred.
         */
        const val PAGE_SIZE = 100

        /** At [PAGE_SIZE] a page, two hundred thousand items — far past any real library. */
        const val MAX_PAGES = 2_000

        /**
         * PRODUCT_SPEC LIB-002 — server search hits per library.
         *
         * Search enriches a list the cache already produced, and a user scanning results does not read
         * past the first screenful. Fetching more would cost a larger response and a longer write for
         * rows nobody scrolls to.
         */
        const val SEARCH_LIMIT = 25
    }
}
