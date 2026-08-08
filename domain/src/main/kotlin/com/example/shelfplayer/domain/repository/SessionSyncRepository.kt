package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SessionSyncDiagnostics
import com.example.shelfplayer.core.model.playback.SyncTrigger
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-005 — getting a position back to the server, and never losing it on the way.
 *
 * ### Separate from [PlaybackRepository], deliberately
 *
 * [PlaybackRepository.recordPosition] is the local journal: one row, no network, and it must not be able to
 * fail for a reason the network can produce. This interface is the *remote* half, and it is allowed to fail —
 * that is what the outbox is for. Folding the two together would put a network call on the path product
 * priority 2 depends on.
 *
 * ### The two ways a position reaches the server
 *
 * 1. **[syncOpenSession]**, against the id the server issued when playback started. Live, while online.
 * 2. **[drainOutbox]**, against ids *this device* generated, over the offline route. That is the only way a
 *    session recorded with no connection can ever be uploaded, because route 1 needs an id the server never
 *    issued (PLAY-005).
 *
 * Both write the same table first. A sync that is attempted and fails is indistinguishable, afterwards, from
 * one that was never attempted — unless the row was written before the request went out.
 */
interface SessionSyncRepository {

    /**
     * Records the start of a listening session and returns the local id it was filed under.
     *
     * The id is a UUIDv4 generated here (PLAY-005), not the server's. [remoteSessionId] is stored alongside
     * it and is `null` for a session opened with no connection — the two are separate columns because they
     * are separate facts, and only one of them exists offline.
     *
     * @return the local session id, or a failure when no profile is active.
     */
    suspend fun openSession(
        bookId: LibraryItemId,
        remoteSessionId: String?,
        title: String,
        author: String?,
        position: kotlin.time.Duration,
        duration: kotlin.time.Duration,
        startedAt: Instant,
    ): AppResult<String>

    /**
     * Records a position against a session already opened, and returns whether the server took it.
     *
     * The local row is updated first and unconditionally. A failure here means the row stays queued, which is
     * exactly what the outbox is: there is no path by which a position is attempted and then forgotten.
     *
     * @param updatedAt the honest moment the position was observed. Never "now at upload time": the server
     *   resolves conflicts on this value, and stamping it late would let a stale position win (PLAY-004).
     */
    suspend fun syncOpenSession(
        sessionId: String,
        progress: SessionProgress,
        updatedAt: Instant,
        trigger: SyncTrigger,
    ): AppResult<Unit>

    /**
     * Finalizes a session: one last position, and the row leaves the `Open` state.
     *
     * Called when playback moves to another book, when the service is destroyed, and at the end of a book. A
     * session that is never closed is still uploaded — the drain includes open rows — so this is about
     * accuracy rather than about whether the listening survives.
     */
    suspend fun closeSession(
        sessionId: String,
        progress: SessionProgress,
        updatedAt: Instant,
        trigger: SyncTrigger,
    ): AppResult<Unit>

    /**
     * PRODUCT_SPEC PLAY-005 — uploads every queued session, then compacts what the server has accepted.
     *
     * Retried by being called again rather than by looping here: the queue is durable, so "retry" is the next
     * drain, and a drain that blocked on a server that is down would hold whatever called it.
     *
     * A session the server accepts is marked synced **even if it declined the position** — that is the
     * conflict rule working, not a failure, and retrying into it would be the app arguing with the server
     * (PLAY-004).
     *
     * @return how many sessions the server accepted.
     */
    suspend fun drainOutbox(): AppResult<Int>

    /**
     * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — the outbox as numbers, for the active profile.
     *
     * Counts and timestamps. No titles: the screen exists to prove progress is queued, and naming the books
     * would not make it more convincing (PRODUCT_SPEC 14.5).
     */
    fun observeDiagnostics(): Flow<SessionSyncDiagnostics>
}
