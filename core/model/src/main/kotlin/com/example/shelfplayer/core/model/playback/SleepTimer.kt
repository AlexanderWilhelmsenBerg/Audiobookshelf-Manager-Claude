package com.example.shelfplayer.core.model.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-008 — how a sleep timer was set.
 *
 * Two shapes rather than one duration, because they answer differently to being restarted. A
 * [Fixed] timer restarts to its own length; an [EndOfChapter] timer has no length of its own — it ends
 * where the book's structure says, and restarting it means "the chapter after this one".
 */
sealed interface SleepTimerMode {
    /** A wall-clock length. PLAY-008's 5/10/15/30/45/60/90-minute options and the custom one. */
    data class Fixed(val length: Duration) : SleepTimerMode

    /**
     * Stop at the end of the chapter being listened to.
     *
     * PLAY-008 requires this to handle "malformed or absent chapters gracefully". A book with no
     * chapters has no end-of-chapter, and the app says so rather than silently behaving as a
     * fixed timer of some invented length — see `SleepTimerController`.
     */
    data object EndOfChapter : SleepTimerMode
}

/**
 * PRODUCT_SPEC PLAY-008 — the live timer, for the player and the notification.
 *
 * [remaining] is recomputed rather than stored: an [SleepTimerMode.EndOfChapter] timer's remaining time
 * moves with the playback position and with the speed, so a decrementing counter would drift from the
 * moment the listener changed either.
 */
data class SleepTimerState(
    val mode: SleepTimerMode?,
    val remaining: Duration,
    /** Whether the fade-out has begun. The player's volume is below 1.0 while this is true. */
    val isFading: Boolean,
) {
    val isActive: Boolean get() = mode != null

    companion object {
        val Idle = SleepTimerState(mode = null, remaining = Duration.ZERO, isFading = false)
    }
}

/** PRODUCT_SPEC PLAY-008 — why a timer stopped. Recorded, so "it cut out" can be told from "I cancelled it". */
enum class SleepTimerOutcome {
    /** The timer ran down and paused playback. */
    Expired,

    /** The listener turned it off. */
    Cancelled,

    /** Playback ended or the book changed underneath it. */
    PlaybackStopped,

    /** The service went away — a process death, or the user swiping the app off a paused session. */
    Abandoned,
}

/**
 * PRODUCT_SPEC PLAY-008 / SET-002 — one recorded sleep-timer session, kept on the device.
 *
 * ### What this is for
 *
 * "I set a timer last night and the book was still playing this morning" is only answerable if the app
 * wrote down what it did. This is that record: when a timer started, when and why it stopped, and how
 * many times it was pushed back.
 *
 * ### What it deliberately does not carry
 *
 * No title. [bookId] identifies the book for joining against the local library, and the screen that
 * shows this history resolves the title from Room — so a diagnostics export built from these rows
 * carries no media titles unless the user has opted in (PRODUCT_SPEC 14.5, SET-002).
 *
 * @property endedAt `null` while the timer is still running.
 * @property restarts how many times the timer was pushed back, by a shake or by the notification
 *   action. A timer restarted eleven times is a listener fighting to stay awake, and it is the number
 *   that makes a history worth keeping rather than a list of identical rows.
 */
data class SleepTimerSession(
    val id: String,
    val profileId: ProfileId,
    val bookId: LibraryItemId,
    val mode: SleepTimerMode,
    val startedAt: Instant,
    val endedAt: Instant?,
    val outcome: SleepTimerOutcome?,
    val restarts: Int,
) {
    val isRunning: Boolean get() = endedAt == null
}

/**
 * PRODUCT_SPEC SET-002 (Playback: "sleep timer defaults; fade duration; shake-to-extend").
 *
 * Device-wide rather than per profile, unlike the view preferences. A sleep timer is a property of how
 * someone uses their phone at night; it does not belong to whichever account is signed in, and moving
 * it per profile would silently reset it on a profile switch.
 */
data class SleepTimerSettings(
    val defaultLength: Duration,
    val fadeLength: Duration,
    /**
     * PRODUCT_SPEC PLAY-008 — "optional shake-to-extend requires explicit opt-in".
     *
     * Off by default, and the sensor is registered only while a timer is running. Both halves are the
     * requirement: an opt-in that then polls the accelerometer all day is not what was asked for.
     */
    val shakeToRestart: Boolean,
) {
    companion object {
        /** PLAY-008's option list. `null` in the UI's custom slot. */
        val Presets: List<Duration> = listOf(5, 10, 15, 30, 45, 60, 90).map { it.minutes }

        /** PLAY-008: "optional fade-out occurs over 5–30 seconds". */
        val FadeRange: ClosedRange<Duration> = 5.seconds..30.seconds

        /** A custom length still has to be a length. An hour and a half is the longest preset. */
        val LengthRange: ClosedRange<Duration> = 1.minutes..(8 * 60).minutes

        val Default = SleepTimerSettings(
            defaultLength = 30.minutes,
            fadeLength = 10.seconds,
            shakeToRestart = false,
        )
    }
}
