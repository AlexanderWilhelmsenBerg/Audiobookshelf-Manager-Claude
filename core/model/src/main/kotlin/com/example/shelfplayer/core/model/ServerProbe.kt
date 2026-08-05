package com.example.shelfplayer.core.model

/**
 * What `GET /status` reports before any credential is sent (PRODUCT_SPEC AUTH-001, SYNC-001).
 *
 * It lives in `:core:model` rather than next to the gateway because two layers need it: the gateway
 * produces it, and the domain-level `AuthRepository` returns it so a sign-in screen can show the
 * detected version before asking for a password (PRODUCT_SPEC 6.1 step 4). `:domain` cannot see
 * `:core:network`, so a network-module type could not have crossed that boundary.
 *
 * `GET /status` is not in the project's published `openapi.json`; the shape is recorded in
 * `docs/api-compatibility.md` and captured in `contracts/status-initialized.json`.
 *
 * @property isAudiobookshelf whether the response carried the Audiobookshelf application marker. A
 *   host that answers `200` with something else is not a server, and PRODUCT_SPEC AUTH-001 wants the
 *   user to learn that before typing a credential into it.
 * @property serverVersion the version string, or `null` when the server did not report one. Only used
 *   to select known workarounds; capability probes decide behaviour (PRODUCT_SPEC 10.4).
 * @property isInitialized `false` on a server that has not had its first-run setup completed. Signing
 *   in to one cannot succeed, so the UI says so instead of reporting a wrong password.
 * @property authMethods the authentication modes the server offers, e.g. `["local"]`. Persisted as
 *   part of the capability handshake (PRODUCT_SPEC SYNC-001).
 */
data class ServerProbe(
    val isAudiobookshelf: Boolean,
    val serverVersion: String?,
    val isInitialized: Boolean,
    val authMethods: List<String>,
)

/**
 * PRODUCT_SPEC AUTH-001 — an address the user typed, normalized and checked, before any password.
 *
 * PRODUCT_SPEC 6.1 puts four steps before the credential prompt: normalize the URL, check
 * reachability, show the detected version, and show the connection security. This carries all four
 * results, so a sign-in screen can present them without repeating the normalization rules or reaching
 * for a network type.
 *
 * @property serverUrl the normalized base URL, without a trailing slash. This — not the raw input — is
 *   what a profile is created against, so that `https://host` and `https://host/` are one server.
 * @property isCleartext `true` when the connection would not be encrypted. PRODUCT_SPEC 15 leaves the
 *   decision to allow it to build and user policy; reporting it is this type's job.
 * @property wasSchemeAssumed `true` when the user typed no scheme and HTTPS was proposed
 *   (PRODUCT_SPEC AUTH-001), so the UI can say so instead of silently changing the input.
 */
data class ServerCandidate(
    val serverUrl: String,
    val isCleartext: Boolean,
    val wasSchemeAssumed: Boolean,
    val probe: ServerProbe,
)
