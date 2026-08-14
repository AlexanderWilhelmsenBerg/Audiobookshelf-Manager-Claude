package com.example.shelfplayer.core.network.api

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.core.network.gateway.BookmarkApi
import com.example.shelfplayer.core.network.gateway.ProfileConnectionResolver
import com.example.shelfplayer.core.network.http.NetworkErrorMapper
import retrofit2.Response
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC 11.1 — the real [BookmarkApi], against the four 2026-08-13 fixtures.
 *
 * ### Writes only
 *
 * There is no read here. Bookmarks live on the user object, so they arrive with `GET /api/me` and
 * `POST /api/authorize` — calls the app already makes on every profile refresh — and land in
 * [com.example.shelfplayer.core.model.auth.AccountState.bookmarks]. A second read path would be a second
 * thing to keep in step, for a set the app is already given.
 *
 * ### Not retried
 *
 * Like [AbsPlaybackApi]'s session open, and for the same reason: these are writes. A retried create makes
 * a second bookmark — or, given that the position is the key, silently overwrites the first with the same
 * thing, which is worse because it looks like it worked. A failure is reported to the caller, who is
 * holding a listener that just pressed a button.
 *
 * ### What is not logged
 *
 * The book id, and above all the **title**: PRODUCT_SPEC 14.5 keeps private self-hosted data out of logs,
 * and a bookmark's title is the listener's own words about a book. The position is logged, because
 * "the create at 14520 s failed" is what a support report needs and a number of seconds names nothing.
 */
@Singleton
internal class AbsBookmarkApi @Inject constructor(
    private val services: AudiobookshelfServiceFactory,
    private val connections: ProfileConnectionResolver,
    private val errors: NetworkErrorMapper,
    private val logger: Logger,
) : BookmarkApi {

    override suspend fun create(
        profileId: ProfileId,
        bookId: LibraryItemId,
        at: Duration,
        title: String,
    ): AppResult<Bookmark> = withBookmark(profileId, bookId, at, "create") { service, bearer ->
        service.create(bearer, bookId.value, BookmarkRequestDto(time = at.inWholeSeconds, title = title))
    }

    override suspend fun rename(
        profileId: ProfileId,
        bookId: LibraryItemId,
        at: Duration,
        title: String,
    ): AppResult<Bookmark> = withBookmark(profileId, bookId, at, "rename") { service, bearer ->
        service.update(bearer, bookId.value, BookmarkRequestDto(time = at.inWholeSeconds, title = title))
    }

    override suspend fun remove(profileId: ProfileId, bookId: LibraryItemId, at: Duration): AppResult<Unit> = when (
        val transport = transported(profileId) { service, bearer ->
            service.delete(bearer, bookId.value, at.inWholeSeconds)
        }
    ) {
        is AppResult.Failure -> AppResult.Failure(transport.error)
        is AppResult.Success -> accepted(transport.value, at)
    }

    /**
     * The two routes that answer with the bookmark they wrote.
     *
     * Shared because create and rename differ only in the verb: same body, same response, same failure
     * modes. The [bookId] is passed in rather than read back off the response because the response's own
     * `libraryItemId` is the server's echo, and a mapper that trusted it would attach a bookmark to
     * whatever the server said rather than to the book the caller asked about.
     */
    private suspend fun withBookmark(
        profileId: ProfileId,
        bookId: LibraryItemId,
        at: Duration,
        operation: String,
        call: suspend (BookmarkService, String) -> Response<BookmarkDto>,
    ): AppResult<Bookmark> = when (val transport = transported(profileId, call)) {
        is AppResult.Failure -> AppResult.Failure(transport.error)
        is AppResult.Success -> bookmarkFrom(bookId, at, operation, transport.value)
    }

    /**
     * The transport, and only the transport.
     *
     * Separated from the response handling so each half has one job: this one resolves the profile's
     * connection and crosses the network, and its caller decides what the answer means. ADR-0003 —
     * `resultOf` is the app's single exception boundary, and it wraps exactly the call.
     */
    private suspend fun <T> transported(
        profileId: ProfileId,
        call: suspend (BookmarkService, String) -> Response<T>,
    ): AppResult<Response<T>> {
        val connection = connections.connectionFor(profileId)
            ?: return AppResult.Failure(AppError.Authentication())
        return resultOf(onError = errors::fromThrowable) {
            call(services.bookmarkService(connection.serverUrl), bearerOf(connection.accessToken.value))
        }
    }

    /**
     * A written bookmark, or the reason it cannot be used.
     *
     * The position is taken from the **response** rather than from the request, because the server is
     * entitled to have stored something else — and if it did, the app has to hold the value it can later
     * delete rather than the one it asked for.
     */
    private fun bookmarkFrom(
        bookId: LibraryItemId,
        at: Duration,
        operation: String,
        response: Response<BookmarkDto>,
    ): AppResult<Bookmark> {
        if (!response.isSuccessful) return AppResult.Failure(statusErrorFor(response))
        val stored = response.body()?.time
            ?: return AppResult.Failure(
                AppError.ApiCompatibility(
                    summary = "This server answered a bookmark request without a position.",
                    missingField = "time",
                ),
            )
        val body = requireNotNull(response.body())
        logger.debug(
            LogCategory.Sync,
            "A bookmark was written",
            LogField.Public("call", operation),
            LogField.Millis("at", at.inWholeMilliseconds),
        )
        return AppResult.Success(
            Bookmark(
                bookId = bookId,
                at = stored.coerceAtLeast(0).seconds,
                title = body.title.orEmpty(),
                createdAt = Instant.ofEpochMilli(body.createdAt ?: 0L),
            ),
        )
    }

    /** A delete's answer: `200` and the plain text `OK`, or a status the caller can act on. */
    private fun accepted(response: Response<Unit>, at: Duration): AppResult<Unit> {
        if (!response.isSuccessful) return AppResult.Failure(statusErrorFor(response))
        logger.debug(LogCategory.Sync, "A bookmark was deleted", LogField.Millis("at", at.inWholeMilliseconds))
        return AppResult.Success(Unit)
    }

    private fun statusErrorFor(response: Response<*>): AppError =
        errors.fromStatus(response.code(), retryAfterSeconds = response.headers()["Retry-After"]?.toLongOrNull())
}
