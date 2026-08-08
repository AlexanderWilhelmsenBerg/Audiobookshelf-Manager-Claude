package com.example.shelfplayer.core.network.api

import kotlinx.serialization.Serializable

/**
 * Wire types for `POST /api/items/{id}/play`, verified against Audiobookshelf 2.36.0.
 *
 * PRODUCT_SPEC 9.3: these never leave `:core:network`. Recorded as `item-play.json` (one track) and
 * `multi-item-play.json` (two), and `docs/api-compatibility.md` records what those settled.
 *
 * The request shape is the one `scripts/capture-contracts.sh` sent, field for field. That matters more
 * than it looks: the response depends on it. `supportedMimeTypes` is what decides whether the server
 * direct-plays or transcodes, and the fixtures record a **direct play** (`playMethod: 0`) obtained by
 * advertising exactly these three types. A request that advertised something else would be answered
 * with a shape no fixture covers (PRODUCT_SPEC 22.5).
 */
@Serializable
internal data class PlaySessionRequestDto(
    val deviceInfo: PlayDeviceInfoDto,
    val supportedMimeTypes: List<String>,
    val mediaPlayer: String,
    /**
     * Both false, as captured — and deliberately **without** Kotlin defaults.
     *
     * `encodeDefaults` is off in this module's `Json`, so a property that has a default and equals it is
     * omitted from the body entirely. Declaring these `= false` would therefore have sent a *shorter*
     * request than the one the fixtures were captured with, and the server's choice of direct play or
     * transcode is exactly what these fields steer. A missing field is not a false field unless the
     * server says so, and no capture says so (PRODUCT_SPEC 22.4).
     *
     * The values themselves: forcing direct play would fail outright on a codec the device cannot
     * decode, and forcing a transcode would spend a self-hosted server's CPU on every book. Letting the
     * server choose from the advertised types is what produced the only session shape this repository
     * has a fixture for.
     */
    val forceDirectPlay: Boolean,
    val forceTranscode: Boolean,
)

/** PRODUCT_SPEC 14.5 — nothing here is a hardware or advertising identifier. See `PlaybackDevice`. */
@Serializable
internal data class PlayDeviceInfoDto(
    val clientName: String,
    val clientVersion: String,
    val deviceId: String,
    val manufacturer: String,
    val model: String,
    val sdkVersion: Int,
)

/**
 * The open session.
 *
 * Every field is defaulted, as everywhere else in this module: `ignoreUnknownKeys` protects against a
 * server that *adds* a field, not against one that stops sending one, and a missing field has to
 * surface as [com.example.shelfplayer.core.model.AppError.ApiCompatibility] rather than as a
 * deserialization crash inside the media service (PRODUCT_SPEC SYNC-001).
 *
 * @property audioTracks reuses [AudioTrackDto]. The session's tracks carry the same fields as an
 *   expanded library item's — `index`, `startOffset`, `duration`, `mimeType`, `contentUrl`, `exclude` —
 *   and both fixtures confirm it. One DTO rather than two that would drift apart.
 * @property chapters the session-level array, on the **global** book timeline. Each track also carries
 *   its own chapter list scoped to that file; PLAY-003 wants this one.
 * @property startTime seconds. Where the server says to resume, seeded from stored progress — the
 *   capture wrote `128.25` with `PATCH /api/me/progress/{id}` moments earlier and got it straight back.
 * @property playMethod `0` is direct play, which is the only value any capture has produced. What the
 *   others mean is unobserved and must not be assumed (PRODUCT_SPEC 22.4).
 */
@Serializable
internal data class PlaybackSessionDto(
    val id: String? = null,
    val libraryItemId: String? = null,
    val displayTitle: String? = null,
    val displayAuthor: String? = null,
    val coverPath: String? = null,
    val startTime: Double = 0.0,
    val duration: Double = 0.0,
    val playMethod: Int = 0,
    val audioTracks: List<AudioTrackDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
)

/**
 * `POST /api/session/{id}/sync` and `/close`, captured as `session-sync.json` and `session-close.json`.
 *
 * Both routes take this body and both answer **`200` with a `text/plain` body of `OK`** — no JSON, no
 * echo of the accepted position. The client therefore treats `200` as "accepted" and reads `/api/me` if
 * it wants to know what the server actually stored.
 *
 * @property currentTime seconds, fractional. The **global** book position, not a position in a file.
 * @property timeListened seconds of audio actually played since the last sync. Whether the server
 *   accumulates it across syncs is **not settled** by the capture — both requests sent the same value,
 *   which an accumulating counter and an idempotent one are indistinguishable under.
 * @property duration seconds. Sent because the capture sent it; the server already knows it.
 */
@Serializable
internal data class SessionSyncRequestDto(val currentTime: Double, val timeListened: Long, val duration: Double)

/**
 * PRODUCT_SPEC PLAY-005 — one offline session, for `POST /api/session/local-all`.
 *
 * ### Why the id is the client's
 *
 * `/api/session/{id}/sync` needs an id the *server* issued, which a session recorded with no network by
 * definition does not have. `/api/session/local` and `/local-all` take a session whose id the **client**
 * generated and treat an unseen one as new — which is what makes a retry idempotent: the second attempt
 * carries the same id and is recognised as the same session rather than duplicated.
 *
 * ### `updatedAt` is load-bearing
 *
 * The server applies the newer `updatedAt` and declines older progress. The capture proved it by
 * accident: it sent `updatedAt: 0` and got `progressSynced: false` back with `success: true` — the
 * session was stored and its progress was *not* applied. So this field must be the honest moment the
 * position was recorded, and a device with a fast clock wins conflicts it should lose (see
 * `ClockSkew`).
 */
@Serializable
internal data class LocalSessionDto(
    val id: String,
    val libraryItemId: String,
    val episodeId: String? = null,
    val mediaItemId: String,
    val mediaItemType: String,
    val displayTitle: String,
    val displayAuthor: String?,
    val duration: Double,
    val currentTime: Double,
    val timeListening: Long,
    /** Epoch millis. */
    val startedAt: Long,
    val updatedAt: Long,
    val mediaPlayer: String,
    val deviceInfo: PlayDeviceInfoDto,
)

@Serializable
internal data class LocalSessionBatchDto(val sessions: List<LocalSessionDto>)

/**
 * The per-session result `local-all` answers with, captured as `session-local-all.json`.
 *
 * This is why the outbox uses the batch route even for a single session: `POST /api/session/local`
 * answers `200` with an **empty body**, so a queue draining through it cannot tell "stored" from
 * "ignored". `local-all` reports both.
 *
 * @property success the session was accepted. This is what the outbox drains on.
 * @property progressSynced whether the *position* was applied, which is a different question — a
 *   session whose `updatedAt` is older than what the server holds comes back `success: true` with
 *   `progressSynced: false`. Recorded, and deliberately **not** treated as a failure: the server
 *   declining older progress is PLAY-004's conflict rule working, not an error to retry into.
 */
@Serializable
internal data class LocalSessionResultDto(
    val id: String? = null,
    val success: Boolean = false,
    val progressSynced: Boolean = false,
)

@Serializable
internal data class LocalSessionBatchResponseDto(val results: List<LocalSessionResultDto> = emptyList())
