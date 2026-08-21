package com.example.shelfplayer.core.model.realtime

import com.example.shelfplayer.core.model.auth.AccountState

/**
 * PRODUCT_SPEC SYNC-002 — something the server told us without being asked.
 *
 * Two events. [AccountChanged] was captured — `contracts/socket-event-after-progress.json` recorded it.
 * [TaskChanged] is **source-derived** rather than captured: MGR-007's outcome arrives nowhere else, and no
 * account this project can reach is allowed to start the task that produces it. `docs/gaps.md` says so.
 *
 * Item changes, library scans and session events have still never been seen and are therefore not modelled
 * (PRODUCT_SPEC 22.4).
 */
sealed interface RealtimeEvent {
    /**
     * The account changed, and the frame carries **all** of it.
     *
     * Not a progress delta — the capture showed a REST progress write coming back as the entire user
     * object, permissions and account state included. That is why one event serves three purposes:
     * a position played elsewhere, a grant changed on the server, and an account disabled.
     */
    data class AccountChanged(val account: AccountState) : RealtimeEvent

    /**
     * PRODUCT_SPEC MGR-007 — a long-running server task started, or ended.
     *
     * The only place an embed's outcome appears. The route that starts it answers `200` the moment the task
     * is queued, so without this the app could report "asked" and never "done".
     */
    data class TaskChanged(val task: ServerTask) : RealtimeEvent
}

/**
 * PRODUCT_SPEC MGR-007 / 14.5 — a server task, with everything private left out.
 *
 * ### What is deliberately missing
 *
 * The frame carries `title`, `description`, `titleSubs` and `descriptionSubs`, and the description is
 * **"Embedding metadata in audiobook \"<the book's title>\""**. A book title is private self-hosted data
 * (PRODUCT_SPEC 14.5), so none of those four fields is modelled: there is nothing here to log, nothing to
 * put in a diagnostics report, and nothing for a future change to leak by accident.
 *
 * What is left is enough to act on — which task, about which item, and whether it worked.
 *
 * @property action the server's own word, `embed-metadata` for MGR-007. Kept verbatim rather than mapped to
 *   an enum, because a task kind this build has never heard of must survive being ignored.
 * @property libraryItemId the correlation key, from the frame's `data.libraryItemId`. Null for a task that
 *   is not about one item — a library scan, for instance — which is a task this app has nothing to do with.
 * @property hasError whether the server attached an error string, *without* carrying the string. The text
 *   is a server message that can quote a filename, and a filename is a path inside somebody's library.
 */
data class ServerTask(
    val id: String,
    val action: String,
    val libraryItemId: String?,
    val isFinished: Boolean,
    val isFailed: Boolean,
    val hasError: Boolean,
) {
    /** MGR-007 — the one action this app starts and therefore the one it waits for. */
    val isEmbedMetadata: Boolean get() = action == EMBED_METADATA

    companion object {
        const val EMBED_METADATA = "embed-metadata"
    }
}

/**
 * PRODUCT_SPEC SYNC-002 — whether the realtime connection is up, and honestly so.
 *
 * [Connecting] is separate from [Disconnected] because a client that reconnects with backoff spends
 * real time in it, and a UI that showed "disconnected" throughout would be reporting a failure during
 * an ordinary recovery.
 */
enum class RealtimeStatus {
    /** Never started, or deliberately stopped — no profile, or the app is in the background. */
    Idle,

    Connecting,

    /** Connected *and* authenticated. A socket that is open but unauthenticated receives nothing. */
    Connected,

    /** The connection dropped and a retry is scheduled. REST remains the source of truth throughout. */
    Disconnected,
}
