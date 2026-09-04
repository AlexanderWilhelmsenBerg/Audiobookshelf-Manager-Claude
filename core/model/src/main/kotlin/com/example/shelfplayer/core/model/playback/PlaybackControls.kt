package com.example.shelfplayer.core.model.playback

import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-007 — playback speed, as a value that cannot be invalid.
 *
 * ### Why a type rather than a `Float`
 *
 * The requirement is "0.5× to 3.0× in 0.05 increments", and a bare float satisfies none of it. Three
 * separate places set speed — the player sheet, a per-book override, the profile default — and each would
 * need the same clamp and the same rounding. [of] is that arithmetic, written once.
 *
 * ### The rounding matters
 *
 * A slider produces `1.8499999`. Sending that to ExoPlayer is harmless, but *storing* it means the label
 * reads `1.85×` while the stored value is not one of the steps, and the next increment lands off-grid and
 * stays there. Snapping on construction keeps every stored speed on the grid the requirement defines.
 */
@JvmInline
value class PlaybackSpeed private constructor(val value: Float) {

    /** `1.0×`, `1.85×` — trailing zeros trimmed to two decimals, which is what the step size needs. */
    fun label(): String {
        val hundredths = (value * HUNDRED).roundToInt()
        val whole = hundredths / HUNDRED
        val fraction = hundredths % HUNDRED
        return when {
            fraction == 0 -> "$whole"
            fraction % TEN == 0 -> "$whole.${fraction / TEN}"
            else -> "$whole.${fraction.toString().padStart(2, '0')}"
        }
    }

    /** The next step up, clamped at [MAX]. */
    fun increased(): PlaybackSpeed = of(value + STEP)

    /** The next step down, clamped at [MIN]. */
    fun decreased(): PlaybackSpeed = of(value - STEP)

    val isDefault: Boolean get() = this == Normal

    companion object {
        const val MIN: Float = 0.5f
        const val MAX: Float = 3.0f

        /** PLAY-007's increment. Every stored speed is a multiple of this. */
        const val STEP: Float = 0.05f

        private const val HUNDRED = 100
        private const val TEN = 10

        /**
         * Clamps to the range and snaps to the nearest step.
         *
         * The snap is done in hundredths as an `Int` rather than by dividing floats, because
         * `0.05f * 37` is not `1.85f` and a comparison of two such values fails in a way that is very hard
         * to see in a test failure message.
         */
        fun of(value: Float): PlaybackSpeed {
            val clamped = value.coerceIn(MIN, MAX)
            val steps = (clamped / STEP).roundToInt()
            return PlaybackSpeed(steps * STEP)
        }

        val Normal: PlaybackSpeed = of(1.0f)

        /**
         * The speeds a chooser offers, for a listener who does not want to nudge a slider.
         *
         * Not every step — sixty-one of those is a list nobody reads. These are the ones people actually
         * use, and the slider covers everything between.
         */
        val Presets: List<PlaybackSpeed> =
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f).map(::of)
    }
}

/**
 * PRODUCT_SPEC PLAY-007 — how far the skip controls move, each direction independently.
 *
 * ### The default is 30/30, not 15/30
 *
 * PLAY-007 says "defaults are 15 seconds back and 30 seconds forward". The project owner asked for thirty
 * both ways, twice, and this is their app. ADR-0015 records the departure; the requirement's substance —
 * both directions configurable from 5 to 120 seconds — is met exactly.
 */
data class SkipIntervals(val back: Duration, val forward: Duration) {
    companion object {
        /** PLAY-007: "independently configurable from 5–120 seconds". */
        val Range: ClosedRange<Duration> = 5.seconds..120.seconds

        /** What a chooser offers. The range still allows anything between. */
        val Presets: List<Duration> = listOf(5, 10, 15, 30, 45, 60, 90, 120).map { it.seconds }

        /** See the class note: the owner's 30/30, not the requirement's 15/30. */
        val Default = SkipIntervals(back = 30.seconds, forward = 30.seconds)

        /** Clamps both directions into [Range], so a stored value from any build is usable. */
        fun of(back: Duration, forward: Duration) = SkipIntervals(
            back = back.coerceIn(Range),
            forward = forward.coerceIn(Range),
        )
    }
}

/**
 * PRODUCT_SPEC PLAY-009 — how far to rewind when a listener comes back after a pause.
 *
 * ### Buckets rather than a formula
 *
 * The requirement gives four bands and an example of each. A continuous function of pause length would be
 * harder to explain and impossible to check: "I paused for eleven minutes and it went back nine seconds"
 * is a bug report nobody can act on. Four numbers are four numbers.
 *
 * ### Off by default, and that is a requirement rather than a taste
 *
 * PLAY-009 says so, and the reason is product priority 1: a rewind the listener did not ask for is the app
 * moving their position, which is the one thing the whole progress design exists to prevent. It has to be
 * chosen.
 *
 * @property afterShortPause under [ShortPause]. The example is zero, and zero is the honest default: a
 *   listener who paused to answer a question has not lost their place.
 */
data class AutoRewind(
    val isEnabled: Boolean,
    val afterShortPause: Duration,
    val afterMediumPause: Duration,
    val afterLongPause: Duration,
    val afterVeryLongPause: Duration,
) {
    /**
     * How far to rewind after a pause of [pausedFor], before any clamping to a chapter or book start.
     *
     * [Duration.ZERO] when disabled, which lets the caller apply this unconditionally rather than checking
     * the flag at every call site — the check that gets forgotten in one of them.
     */
    fun amountFor(pausedFor: Duration): Duration = when {
        !isEnabled -> Duration.ZERO
        pausedFor < ShortPause -> afterShortPause
        pausedFor < MediumPause -> afterMediumPause
        pausedFor < LongPause -> afterLongPause
        else -> afterVeryLongPause
    }

    companion object {
        /** PLAY-009's bands: under 2 minutes, 2–10, 10–60, over 60. */
        val ShortPause: Duration = 2.minutes
        val MediumPause: Duration = 10.minutes
        val LongPause: Duration = 60.minutes

        /** A rewind longer than this is a seek, and the user should do it themselves. */
        val Range: ClosedRange<Duration> = Duration.ZERO..60.seconds

        val Presets: List<Duration> = listOf(0, 5, 10, 15, 30, 45, 60).map { it.seconds }

        /** PLAY-009: "disabled by default", with the requirement's own example amounts ready to use. */
        val Default = AutoRewind(
            isEnabled = false,
            afterShortPause = Duration.ZERO,
            afterMediumPause = 5.seconds,
            afterLongPause = 15.seconds,
            afterVeryLongPause = 30.seconds,
        )
    }
}

/**
 * PRODUCT_SPEC PLAY-006 — how much a remote stream buffers ahead.
 *
 * ### Why the presets carry their own numbers
 *
 * The requirement lists them, so they are data rather than a comment. [Automatic] is the default and means
 * "leave Media3's own defaults alone", which is a different thing from any particular pair of numbers — so
 * it carries none.
 *
 * @property minimumBuffer how much audio to hold before the player stops loading.
 * @property maximumBuffer the upper bound it loads to.
 * @property bufferForPlayback how much must be buffered before playback starts.
 * @property bufferForRebuffer how much must be buffered before playback resumes after a stall.
 */
enum class BufferPreset(
    val minimumBuffer: Duration,
    val maximumBuffer: Duration,
    val bufferForPlayback: Duration,
    val bufferForRebuffer: Duration,
) {
    /** Media3's own defaults, untouched. The other fields are unused and are the library's values. */
    Automatic(
        minimumBuffer = 50.seconds,
        maximumBuffer = 50.seconds,
        bufferForPlayback = 2.5.seconds,
        bufferForRebuffer = 5.seconds,
    ),
    Low(15.seconds, 30.seconds, 2.seconds, 4.seconds),
    Standard(30.seconds, 60.seconds, 2.5.seconds, 5.seconds),
    High(60.seconds, 180.seconds, 2.5.seconds, 5.seconds),
    VeryHigh(120.seconds, 300.seconds, 2.5.seconds, 5.seconds),
    ;

    /**
     * PRODUCT_SPEC PLAY-006 — "invalid combinations are rejected: minimum must not exceed maximum; start
     * thresholds must not exceed minimum".
     *
     * Checked rather than assumed even for the built-in presets, because this is the predicate the
     * Advanced option will need and a preset that violated it would be a `DefaultLoadControl` that throws
     * at construction — a crash on the next play, not a warning.
     */
    val isValid: Boolean
        get() = minimumBuffer <= maximumBuffer &&
            bufferForPlayback <= minimumBuffer &&
            bufferForRebuffer <= minimumBuffer

    companion object {
        val Default = Automatic

        /** An unrecognized stored value reads back as the default (PRODUCT_SPEC SYNC-001). */
        fun byNameOrDefault(name: String?): BufferPreset = entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * PRODUCT_SPEC SET-002 (Playback) — every playback preference in one value.
 *
 * One type because they are read together, by one screen and by one player. Splitting them would mean four
 * flows into the same `combine` and four chances for the player to be holding one setting from before a
 * change and three from after.
 */
data class PlaybackSettings(
    val defaultSpeed: PlaybackSpeed = PlaybackSpeed.Normal,
    val skips: SkipIntervals = SkipIntervals.Default,
    val autoRewind: AutoRewind = AutoRewind.Default,
    val buffer: BufferPreset = BufferPreset.Default,
    /** PRODUCT_SPEC PLAY-002 — what a transient interruption does. Pause unless the listener says otherwise. */
    val focusBehaviour: FocusBehaviour = FocusBehaviour.Default,
    /** PRODUCT_SPEC ROUTE-003 — what opening the app does to the player. */
    val startupMode: StartupMode = StartupMode.Default,
    /**
     * PRODUCT_SPEC 6.4 step 6 — whether finishing a book starts the next one in its series.
     *
     * **On by default**, unlike the car auto-play switch this file used to describe, and the difference is
     * worth being explicit about. That one could make audio begin in a silent room with nobody having
     * pressed anything. This one continues audio that is *already playing*, for a listener who is already
     * listening — a series read in order is one long thing, and stopping dead between its parts is the
     * surprise rather than the safe default.
     *
     * The cost is real and is recorded as a risk: somebody who falls asleep at the end of book 3 wakes to
     * book 4 several hours in, with progress on a book they have not heard. The sleep timer is the answer
     * to that and exists (PLAY-008); this switch is the other one.
     */
    val autoAdvanceSeries: Boolean = true,
    /**
     * PRODUCT_SPEC PLAY-002 / ROUTE-002 — whether connecting to a car leaves the book in the headset.
     *
     * A car connecting normally takes the audio with it, because the platform's own routing policy prefers
     * the newly connected car and this app does not fight it. That is right for the driver and wrong for the
     * passenger, and for the listener who walks into their own car still wearing earbuds — PLAY-002's
     * *"playback never unexpectedly moves"* read from the other direction.
     *
     * **Off by default**, and the default is the point: on means the app pins the route, which is a
     * preference the platform may decline and which stays pinned until the headset disconnects. Nobody
     * should get that without choosing it. What "the headset" means is the one the sound was already
     * coming out of — see `HeadsetHold`, which will not move audio to a headset that was merely connected.
     */
    val keepSoundInHeadset: Boolean = false,
) {
    companion object {
        val Default = PlaybackSettings()
    }
}

/**
 * PRODUCT_SPEC PLAY-002 — *"A transient focus loss pauses or ducks according to setting; default is pause."*
 *
 * A transient loss is a navigation prompt, a notification chime, somebody else's app saying something short.
 * The choice is between missing a sentence and hearing it under a beep, and it is genuinely personal: in a
 * car, ducking keeps the book moving through "turn left in 200 metres"; on a walk, pausing means not having
 * to rewind.
 *
 * ### It is expressed as what the audio *is*
 *
 * Media3's focus manager ducks a `TRANSIENT_CAN_DUCK` loss for music and pauses it for speech, so this
 * chooses the declared **content type** rather than intercepting the callback. Fighting the platform's own
 * focus handling would mean giving up `handleAudioFocus` and reimplementing every other case.
 */
enum class FocusBehaviour {

    /** The book stops and waits. Nothing is missed and nothing has to be rewound. */
    Pause,

    /**
     * The book keeps playing, quietly, under whatever interrupted it.
     *
     * Only for a loss the system says *can* be ducked. A phone call is a permanent-enough loss to pause
     * either way, which is the platform's decision and not this setting's.
     */
    Duck,
    ;

    companion object {
        /** PLAY-002 says so in as many words. */
        val Default: FocusBehaviour = Pause
    }
}

/**
 * PRODUCT_SPEC ROUTE-003 — what opening the app does to the player.
 *
 * All three restore *something*; only [ResumeOnOpen] makes a sound, and it is not the default because
 * ROUTE-003 says *"App launch alone never starts playback by default."*
 */
enum class StartupMode {

    /**
     * Opening the app does nothing to the player; a media button still starts the last book.
     *
     * ROUTE-003's *"restore and play only after explicit media command"*, and the default — because it is
     * what the app already did, and because the alternative puts a media notification in the shade every
     * time somebody opens the app to browse.
     */
    OnMediaCommand,

    /** ROUTE-003's *"restore last item paused"*: the book is loaded and waiting, silent, on open. */
    RestorePaused,

    /** ROUTE-003's *"resume automatically when app is opened"*. Chosen deliberately or not at all. */
    ResumeOnOpen,
    ;

    companion object {
        val Default: StartupMode = OnMediaCommand
    }
}
