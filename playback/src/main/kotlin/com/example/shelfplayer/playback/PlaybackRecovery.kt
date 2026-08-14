package com.example.shelfplayer.playback

import androidx.media3.common.PlaybackException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-001, product priority 1 — getting a stopped player playing again.
 *
 * ### The defect this exists for
 *
 * When ExoPlayer hits a playback error it moves to `STATE_IDLE`, and **an idle player ignores everything**.
 * `play()` does nothing. `seekTo()` does nothing. The only way out is `prepare()`.
 *
 * A device run found exactly that: a multi-file book stopped while being seeked around, and then would not
 * start, would not seek, and only came back after loading a different book and returning. Nothing in the app
 * called `prepare()`, so nothing could have. The old `onPlayerError` logged a warning and wrote the
 * position — an honest record of a dead player.
 *
 * ### Why a policy object rather than three lines in the listener
 *
 * Retrying has to be bounded, and the bound has to survive across errors while being *forgotten* once
 * playback works again. That is state with rules, and rules that are wrong here are either an app that gives
 * up on a flaky network or an app that hammers a server in a loop. Both are worth a test.
 *
 * ### What is retried
 *
 * Media3 groups error codes by cause, and only one group is worth retrying: **IO**. A stream that timed out,
 * a connection that dropped, a server that returned a 5xx — all transient, all fixed by asking again. A
 * malformed container or an unsupported codec is not: the file will still be malformed on the fourth
 * attempt, and retrying turns one honest failure into four seconds of pretending.
 *
 * `ERROR_CODE_IO_FILE_NOT_FOUND` and `ERROR_CODE_IO_NO_PERMISSION` are IO errors that are deliberately
 * **not** retried. A file the server does not have is not going to appear, and a 401 needs a new token
 * rather than another attempt — PLAY-003 calls for stopping safely and offering repair, which is what
 * surfacing the failure does.
 */
internal class PlaybackRecovery(private val maxAttempts: Int = MAX_ATTEMPTS) {

    private var attempts = 0

    /** How many times in a row recovery has been attempted without playback resuming. */
    val attemptCount: Int get() = attempts

    /**
     * What to do about [error], and the attempt is counted as taken.
     *
     * @return the delay to wait before re-preparing, or `null` when this error must be surfaced instead.
     */
    fun onError(error: PlaybackException): Duration? {
        if (!isRetryable(error)) return null
        if (attempts >= maxAttempts) return null
        attempts += 1
        // Backoff, because the usual cause is a server or a network that needs a moment. Linear rather than
        // exponential: the ceiling is three attempts, so the difference is a second and a half either way,
        // and a listener staring at a stopped book does not want the third try eight seconds out.
        return BASE_DELAY * attempts
    }

    /**
     * Playback is working. Called on `isPlaying`, which is the only honest evidence — `STATE_READY` can be
     * reached by a player that then fails again on the first byte it needs.
     */
    fun onPlaying() {
        attempts = 0
    }

    /** A new book starts with a clean slate; the previous book's bad luck is not this one's. */
    fun onBookChanged() {
        attempts = 0
    }

    private fun isRetryable(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        -> false
        else -> error.errorCode in IO_ERROR_RANGE
    }

    private companion object {
        /**
         * Three, then the failure is the user's to see.
         *
         * A fourth attempt on a book that has failed three times in a row is not recovery, it is a loop
         * against somebody's self-hosted server.
         */
        const val MAX_ATTEMPTS = 3

        val BASE_DELAY: Duration = 1.seconds

        /**
         * Media3 numbers its error codes in blocks by cause, and 2000–2999 is the IO block —
         * `ERROR_CODE_IO_UNSPECIFIED` through `ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE`. Matching the block
         * rather than listing the codes means a code added in a future Media3 is treated as the IO error it
         * is, instead of silently becoming unretryable.
         */
        val IO_ERROR_RANGE = PlaybackException.ERROR_CODE_IO_UNSPECIFIED..IO_BLOCK_END
    }
}

/** The last code in Media3's IO block. Named here because Media3 exposes no constant for the boundary. */
private const val IO_BLOCK_END = 2999
