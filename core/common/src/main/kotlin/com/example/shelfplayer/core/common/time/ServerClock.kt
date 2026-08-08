package com.example.shelfplayer.core.common.time

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.playback.ClockSkew
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-005 — "clock-skew greater than five minutes is detected and shown in diagnostics".
 *
 * ### Why the app needs to know
 *
 * The server resolves progress conflicts by taking the newer `updatedAt`, and this app sends its own
 * (`OfflineSession.updatedAt`). A device five minutes fast therefore wins every conflict it takes part in,
 * including against a position the listener set *later* on another device. The app must not respond by
 * adjusting the timestamp it sends — that is the same defect facing the other way, and PLAY-004 asks the app
 * to respect the server's rule rather than out-manoeuvre it. What it can do is measure the difference and
 * report it, so an unexplained rewind has an explanation on screen instead of in a bug report.
 *
 * ### Why the `Date` header
 *
 * It is on every HTTP response and it is HTTP's own field (RFC 9110 §6.6.1), so reading it is not a guess
 * about Audiobookshelf and needs no captured fixture (PRODUCT_SPEC 22.4). The alternative — an endpoint that
 * reports server time — would be a request per reading for something already in every response.
 *
 * ### What the measurement is worth
 *
 * It includes the round trip, so a reading of a few hundred milliseconds on a slow connection means "the
 * clocks agree". That is why [record] rejects nothing and why [ClockSkew.Threshold] is five *minutes*: the
 * question is not "are these clocks in sync" but "is this device wrong enough to corrupt a conflict".
 *
 * ### Why it lives here and not in `:core:network`
 *
 * The reading is produced by an HTTP interceptor and read by a settings screen, and neither module can see
 * the other. `:core:common` is what both already depend on.
 */
@Singleton
class ServerClock @Inject constructor(private val logger: Logger) {

    private val reading = MutableStateFlow<ClockSkew?>(null)

    /** The most recent measurement, or `null` before the app has had a response from a server. */
    val skew: StateFlow<ClockSkew?> = reading.asStateFlow()

    /**
     * Records one reading.
     *
     * Latest wins rather than an average. A moving average would smooth over the case that matters — the
     * user corrects their clock and the reading should immediately say so — and the value is a diagnostic
     * rather than an input to arithmetic.
     *
     * A crossing of the threshold is logged once per crossing rather than per response, because this runs on
     * every HTTP exchange and a warning per request would be its own problem.
     */
    fun record(serverTime: Instant, deviceTime: Instant) {
        val next = ClockSkew(
            offset = (serverTime.toEpochMilli() - deviceTime.toEpochMilli()).milliseconds,
            measuredAt = deviceTime,
        )
        val wasSignificant = reading.value?.isSignificant == true
        reading.value = next
        if (next.isSignificant && !wasSignificant) {
            logger.warn(
                LogCategory.Network,
                "This device's clock disagrees with the server by more than five minutes",
                // The offset only. Not the times themselves: a timestamp pair is a fingerprint, and
                // PRODUCT_SPEC 14.5 keeps private self-hosted data out of logs.
                LogField.Millis("offset", next.offset.inWholeMilliseconds),
            )
        }
    }
}
