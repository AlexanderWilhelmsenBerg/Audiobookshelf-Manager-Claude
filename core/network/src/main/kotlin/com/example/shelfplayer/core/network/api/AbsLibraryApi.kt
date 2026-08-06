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
import com.example.shelfplayer.core.network.gateway.LibraryApi
import com.example.shelfplayer.core.network.gateway.ProfileConnection
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import retrofit2.Response
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
    private val clock: AppClock,
    private val logger: Logger,
) : LibraryApi {

    override suspend fun listLibraries(profileId: ProfileId): AppResult<List<Library>> =
        withConnection(profileId) { connection, service ->
            service.libraries(bearerOf(connection.accessToken.value)).toResult { body ->
                LibraryMapper.toLibraries(connection.serverId, body ?: LibrariesResponseDto(), clock.now())
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
     * The catalogue, then one expanded fetch per item.
     *
     * N+1 requests, and there is no alternative that stores playable books: the list endpoint returns
     * `numTracks` rather than the tracks, and PRODUCT_SPEC 2.3 needs the track offsets stored alongside
     * the book or offline resume cannot work.
     */
    override suspend fun listBooks(profileId: ProfileId, libraryId: LibraryId): AppResult<LibrarySnapshot> =
        withConnection(profileId) { connection, service ->
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
            val catalogue = service.items(bearer, libraryId.value)
                .toResult { body -> LibraryMapper.toItemIds(body ?: LibraryItemsResponseDto()) }
            when (catalogue) {
                is AppResult.Failure -> catalogue
                is AppResult.Success -> snapshots(connection, libraryId, service, bearer, catalogue.value)
            }
        }

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
        connection: ProfileConnection,
        libraryId: LibraryId,
        service: LibraryService,
        bearer: String,
        ids: List<LibraryItemId>,
    ): AppResult<LibrarySnapshot> {
        val fetchedAt = clock.now()
        val snapshots = mutableListOf<BookSnapshot>()
        var removed = 0
        var firstFailure: AppError? = null
        var unreachable = 0
        for (id in ids) {
            val response = service.item(bearer, id.value)
            if (response.code() == HTTP_NOT_FOUND) {
                // The item was removed between the listing and this fetch. It is genuinely gone, so
                // omitting it is correct and the repository will soft-delete it — unlike a network
                // failure, which says nothing about whether the item still exists.
                removed++
                continue
            }
            val mapped = response.toResult { body ->
                if (body == null) {
                    AppResult.Failure(
                        AppError.ApiCompatibility(
                            summary = "This server returned an empty response for a library item.",
                            missingField = "item",
                        ),
                    )
                } else {
                    LibraryMapper.toSnapshot(connection.serverId, libraryId, connection.profileId, body, fetchedAt)
                }
            }
            when (mapped) {
                is AppResult.Failure -> {
                    unreachable++
                    if (firstFailure == null) firstFailure = mapped.error
                }

                is AppResult.Success -> snapshots += mapped.value
            }
        }
        logCounts(removed, unreachable, ids.size)
        val failure = firstFailure
        // Nothing came back and something went wrong: that is a failed sync, not an empty library.
        if (snapshots.isEmpty() && failure != null) return AppResult.Failure(failure)
        return AppResult.Success(
            LibrarySnapshot(books = snapshots, removedCount = removed, unreachableCount = unreachable),
        )
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

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
