package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerOutcome
import com.example.shelfplayer.core.model.playback.SleepTimerSession
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-008 / SET-002 — the sleep timer's settings and its history.
 *
 * One interface for both halves because they are read together: the screen that lets someone turn
 * shake-to-restart on is the screen that shows them how often it fired. Splitting them would put two
 * repositories behind one screen for no boundary anyone benefits from.
 *
 * **Nothing here runs a timer.** The countdown lives with the player, because it is the player it has
 * to stop; this is the storage behind it.
 */
interface SleepTimerRepository {
    fun observeSettings(): Flow<SleepTimerSettings>

    suspend fun setDefaultLength(length: Duration): AppResult<Unit>

    suspend fun setFadeLength(length: Duration): AppResult<Unit>

    /** PRODUCT_SPEC PLAY-008 — "requires explicit opt-in". This is that opt-in. */
    suspend fun setShakeToRestart(enabled: Boolean): AppResult<Unit>

    /**
     * PRODUCT_SPEC PLAY-008 / PLAY-009 — how far to rewind when the timer stops the book. Zero is off.
     *
     * Its own setting rather than a reuse of the auto-rewind bands, because the question is different:
     * those ask "how long was the pause", and a sleep timer's answer is always "you were asleep".
     */
    suspend fun setRewindOnStop(length: Duration): AppResult<Unit>

    /**
     * The most recent [limit] timers this profile set, newest first.
     *
     * Empty with no active profile rather than a failure: a history nobody is signed in to read is not
     * an error state, it is an empty list.
     */
    fun observeRecentSessions(limit: Int = DEFAULT_HISTORY): Flow<List<SleepTimerSession>>

    /**
     * Records that a timer started, and returns the id the later calls are addressed to.
     *
     * Returning the id rather than taking one keeps the identifier's generation in one place, and means
     * a caller cannot record two sessions under the same id by reusing a variable.
     */
    suspend fun recordStarted(bookId: LibraryItemId, mode: SleepTimerMode): AppResult<String>

    /** A shake or a notification action pushed the timer back. Counted, not logged as a new session. */
    suspend fun recordRestarted(sessionId: String): AppResult<Unit>

    suspend fun recordEnded(sessionId: String, outcome: SleepTimerOutcome): AppResult<Unit>

    /**
     * Closes anything left running by a previous process, and prunes the history.
     *
     * Called once at startup. A timer that was running when the app died has no end, and leaving it
     * open would make every later read show a timer that is not running.
     */
    suspend fun closeOrphanedSessions(): AppResult<Int>

    companion object {
        /** Enough to see a pattern over a fortnight of listening, not enough to be a scrolling chore. */
        const val DEFAULT_HISTORY = 30

        /** PRODUCT_SPEC 13.4 — the history is bounded rather than growing for the life of the install. */
        const val RETAINED_HISTORY = 200
    }
}
