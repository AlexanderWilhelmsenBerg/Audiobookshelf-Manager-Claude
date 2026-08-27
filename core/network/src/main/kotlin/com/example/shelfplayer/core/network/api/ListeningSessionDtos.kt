package com.example.shelfplayer.core.network.api

import kotlinx.serialization.Serializable

/**
 * Wire types for `GET /api/me/listening-sessions`, captured against Audiobookshelf 2.36.0 on 2026-08-27.
 *
 * PRODUCT_SPEC 9.3: these never leave `:core:network`. The fixture is `me-listening-sessions.json` and
 * `ListeningSessionContractTest` replays it.
 *
 * ### What this endpoint is for, and why the app already had a worse answer
 *
 * The history pane's remote rows are currently *derived*, in `LibrarySnapshotWriter.recordRemoteChange`, by
 * diffing a book's stored progress against the progress a sync just fetched. That works and it has two
 * holes it cannot close:
 *
 *  - **It needs a previous row.** `recordRemoteChange` returns early when `before == null`, so a book
 *    listened to somewhere else that this device has never played produces no history at all.
 *  - **It only sees the endpoints.** Two sessions between one sync and the next collapse into a single
 *    row, and a session that started and finished in that window is invisible.
 *
 * This endpoint is the server's own record rather than a reconstruction: one entry per session, with when
 * it started, how long was listened, and on what.
 *
 * ### Every field is defaulted
 *
 * As everywhere in this module. `ignoreUnknownKeys` protects against a server that *adds* a field, not one
 * that stops sending one, and a missing field has to surface as `AppError.ApiCompatibility` rather than as
 * a deserialization crash (SYNC-001).
 */

/**
 * The paged envelope.
 *
 * @property total sessions the account has, across all pages — not the size of [sessions].
 */
@Serializable
internal data class ListeningSessionsResponseDto(
    val sessions: List<ListeningSessionDto>? = null,
    val total: Int? = null,
    val page: Int? = null,
    val itemsPerPage: Int? = null,
    val numPages: Int? = null,
)

/**
 * One listening session.
 *
 * ### The fields this app reads, and the units the capture settled
 *
 * @property id the session's own id. Its identity, and what makes merging into a local history idempotent
 *   — the same session fetched twice must not become two rows.
 * @property libraryItemId which book. The join to everything else.
 * @property timeListening **seconds** actually listened, which is not the same as elapsed wall time: a
 *   paused session accrues none. This is the number that makes a session worth showing.
 * @property startTime **seconds**, the position the session opened at.
 * @property currentTime **seconds**, the position it reached. `startTime` to `currentTime` is the span the
 *   history row describes.
 * @property startedAt epoch **milliseconds**. `updatedAt` is the same clock.
 * @property displayTitle the book's title as the server rendered it — private self-hosted data, so it is
 *   carried for display and never logged (14.5).
 *
 * ### What is deliberately not modelled
 *
 * `deviceInfo` carries an `ipAddress`, which is the one field in this response that could identify a
 * person's network. [ListeningSessionDeviceDto] models the two names a listener would recognise and
 * nothing else, so the address cannot be stored or logged by accident — the same rule that keeps
 * `GET /api/users`' token out of `UserDto`.
 *
 * `chapters`, `mediaMetadata`, `coverPath` and `bookId` are all present in the response and all redundant
 * here: the app already holds them for a book it knows, and for a book it does not, a history row is not
 * where a library entry should come from.
 */
@Serializable
internal data class ListeningSessionDto(
    val id: String? = null,
    val libraryItemId: String? = null,
    val displayTitle: String? = null,
    val displayAuthor: String? = null,
    val mediaType: String? = null,
    val duration: Double? = null,
    val timeListening: Double? = null,
    val startTime: Double? = null,
    val currentTime: Double? = null,
    val startedAt: Long? = null,
    val updatedAt: Long? = null,
    val deviceInfo: ListeningSessionDeviceDto? = null,
)

/**
 * Who was listening, as much of it as this app has any business keeping.
 *
 * @property deviceId the server's own id for the device. What lets a session this phone created be told
 *   apart from one another device created, which is the difference between a useful remote history and a
 *   duplicate of the local one.
 * @property deviceName a human name — "Pixel 8", "capture capture". Shown, never logged.
 * @property clientName the app that played it, e.g. `ShelfPlayer` or the web client.
 */
@Serializable
internal data class ListeningSessionDeviceDto(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val clientName: String? = null,
)
