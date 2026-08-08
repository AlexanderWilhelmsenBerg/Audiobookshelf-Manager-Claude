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
