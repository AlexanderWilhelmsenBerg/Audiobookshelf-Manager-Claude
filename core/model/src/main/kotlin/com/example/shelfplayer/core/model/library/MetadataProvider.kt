package com.example.shelfplayer.core.model.library

/**
 * PRODUCT_SPEC MGR-003 — one metadata source this server offers.
 *
 * ### Why the user picks, rather than the app defaulting
 *
 * The app hardcoded `google` until 2026-08-16, on the reasoning that it is the server's own default and
 * needs no configuration. A run against a real deployment showed why that is not enough: **Google returned
 * nothing there**, on every query, while Audible returned six results for the same title. A provider's
 * reachability is a property of the server's own outbound network — the same reason the websocket is probed
 * rather than inferred — and a single hardcoded source turns "this deployment cannot reach Google" into
 * "this book has no metadata anywhere".
 *
 * @property id the slug a search request sends. Custom providers an administrator configured appear here
 *   with a `custom-` prefix and a generated id, which is exactly why the list is read from the server
 *   rather than written down in this app.
 */
data class MetadataProvider(val id: String, val displayName: String) {
    /** Configured by the server's administrator rather than built in. Worth showing as such. */
    val isCustom: Boolean get() = id.startsWith(CUSTOM_PREFIX)

    private companion object {
        const val CUSTOM_PREFIX = "custom-"
    }
}
