package com.example.shelfplayer.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/** The real session/controller boundary must not advertise guarded Play before the service commits it. */
@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ResumeFreshnessPlayerMediaControllerTest {

    @Test
    fun `controller remains paused while service Play preparation is pending`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val delegate = ExoPlayer.Builder(context).build()
        delegate.setMediaItem(MediaItem.fromUri("file:///controller-resume-test"))
        val preparation = SettableFuture.create<Boolean>()
        val preparationStarted = AtomicBoolean(false)
        val forwarding = ResumeFreshnessPlayer(
            delegate = delegate,
            preparePlay = {
                preparationStarted.set(true)
                preparation
            },
            consumeFreshStart = { false },
            invalidate = { true },
            onPlayWhenReadyRequest = { true },
            skipIntervals = { SkipIntervals(30.seconds, 30.seconds) },
            prepareCurrentOutput = { Futures.immediateVoidFuture() },
            movementEffects = unprotectedMovementEffects(delegate),
        )
        val session = MediaSession.Builder(context, forwarding).build()
        var controller: MediaController? = null
        try {
            val connected = connect(context, session)
            controller = connected
            connected.play()
            pumpMainLooperUntil { preparationStarted.get() }

            assertFalse(delegate.playWhenReady)
            assertFalse(forwarding.playWhenReady)
            assertFalse(connected.playWhenReady)

            preparation.set(false)
            pumpMainLooperUntil { !connected.playWhenReady }
            assertFalse(connected.playWhenReady)
        } finally {
            preparation.cancel(true)
            controller?.release()
            session.release()
            delegate.release()
        }
    }

    private fun connect(context: Context, session: MediaSession): MediaController {
        val controllerFuture = MediaController.Builder(context, session.token).buildAsync()
        val task = FutureTask<MediaController> { controllerFuture.get() }
        Thread(task, "resume-freshness-controller-connect").apply {
            isDaemon = true
            start()
        }
        pumpMainLooperUntil { task.isDone }
        return assertNotNull(task.get())
    }

    private fun pumpMainLooperUntil(condition: () -> Boolean) {
        val mainLooper = shadowOf(Looper.getMainLooper())
        var remaining = MAX_MAIN_LOOPER_PUMPS
        while (!condition() && remaining-- > 0) {
            mainLooper.idle()
            LockSupport.parkNanos(POLL_NANOS)
        }
        check(condition()) { "Media3 controller state did not settle" }
    }

    private companion object {
        const val MAX_MAIN_LOOPER_PUMPS = 5_000
        const val POLL_NANOS = 1_000_000L
    }
}
