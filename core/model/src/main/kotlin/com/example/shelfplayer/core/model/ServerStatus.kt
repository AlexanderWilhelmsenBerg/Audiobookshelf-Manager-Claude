package com.example.shelfplayer.core.model

/**
 * PRODUCT_SPEC LIB-002 / SYNC-001 — whether the user's server is answering.
 *
 * Three states rather than a boolean, because [Unknown] is a real and common answer: before anything
 * has been attempted, and whenever the device has no network at all. Collapsing it into "unreachable"
 * would blame the server for the phone being in a lift.
 *
 * It is not the same question as `NetworkMonitor.isOnline`. A self-hosted server on a home LAN is
 * unreachable over a perfectly good mobile connection, and an indicator that conflated the two would
 * show green while every request failed.
 */
enum class ServerStatus {
    /** Nothing has been attempted, or there is no network to attempt it over. */
    Unknown,

    /** The server answered something — including a refusal, which is still an answer. */
    Reachable,

    /** A request could not reach the server: no route, no response, or a timeout. */
    Unreachable,
}
