package com.example.shelfplayer.core.model.library

/**
 * PRODUCT_SPEC MGR-007 — what asking the server to embed metadata achieved.
 *
 * Three outcomes rather than two, and the third is the interesting one: *the server is already doing it*
 * comes back as a `400`, and treating that as a failure would be wrong twice — nothing went wrong, and the
 * thing the user asked for is in progress. A caller that reported it as an error would have the user press
 * the button again, queueing nothing and reading another error.
 *
 * Notice what is absent: there is no `Finished`. The request cannot produce one. The task runs in the
 * server's own process and says how it went on the websocket, which is MGR-007's *"non-blocking with
 * visible status"* being a property of the API rather than a choice this app made.
 */
enum class EmbedRequest {
    /** The server queued the task. Nothing has been written to any file yet. */
    Accepted,

    /** This item is already queued or being processed. The request changed nothing, and that is correct. */
    AlreadyRunning,
}
