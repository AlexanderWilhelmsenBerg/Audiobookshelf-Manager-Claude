package com.example.shelfplayer.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * Effect-only generation gate for preferred-output requests.
 *
 * The AudioOutputRouter remains the single selection, guard, and route-evidence authority.
 * This class stores no requested output, device identity, playback state, or hardware proof.
 * All effects run on the existing Media3 application dispatcher; a newer owner invalidates
 * queued operations even when consecutive listener selections share the same output ID.
 */
internal class RoutePreferenceEffects(private val playerDispatcher: CoroutineDispatcher) {
    private val generation = AtomicLong()

    fun supersede(): Long = generation.incrementAndGet()

    fun snapshot(): Long = generation.get()

    fun isCurrent(epoch: Long): Boolean = generation.get() == epoch

    suspend fun run(epoch: Long, action: suspend () -> Unit) {
        withContext(playerDispatcher) {
            if (isCurrent(epoch)) action()
        }
    }

    /** Route preference acceptance and measured AudioTrack proof are deliberately separate. */
    suspend fun afterSettle(epoch: Long, duration: Duration, refresh: () -> Unit) {
        delay(duration)
        if (isCurrent(epoch)) refresh()
    }
}
