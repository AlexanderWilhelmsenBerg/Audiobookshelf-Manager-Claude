package com.example.shelfplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.common.log.warn
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * PRODUCT_SPEC PLAY-001 — building a [MediaController] for this app's session.
 *
 * Separated from [PlaybackController] because it is a different job: this one knows how to reach the
 * service and how to turn Media3's `ListenableFuture` into a suspending call; the other knows what to do
 * with the controller once it exists. Keeping the `Context` and the token construction here also means the
 * class that drives playback holds no Android plumbing at all.
 *
 * Public only because [PlaybackController] is, and Hilt injects it there. Nothing outside this module has a
 * reason to build a controller — `MediaController` itself is not visible to them.
 */
@Singleton
class SessionConnector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: Logger,
) {

    /**
     * Bridges Media3's `ListenableFuture` to a suspending call.
     *
     * The two exceptions are caught by name rather than through a `runCatching`, which would swallow
     * cancellation as well (ADR-0003, and the "no broad catch" rule). `ExecutionException` is what a
     * failed session build arrives as; `InterruptedException` is the executor being torn down, and the
     * interrupt is reasserted rather than eaten.
     */
    suspend fun connect(): MediaController? {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        return suspendCancellableCoroutine { continuation ->
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener(
                {
                    val media = try {
                        future.get()
                    } catch (failure: ExecutionException) {
                        connectionFailed(failure)
                        null
                    } catch (failure: InterruptedException) {
                        Thread.currentThread().interrupt()
                        connectionFailed(failure)
                        null
                    }
                    if (continuation.isActive) continuation.resume(media)
                },
                MoreExecutors.directExecutor(),
            )
            continuation.invokeOnCancellation {
                // The returned flag says whether the build was still in flight. Either answer is fine —
                // the continuation is already gone and nothing is waiting for a controller — but it is
                // worth a debug line when a connection is abandoned this way.
                val wasPending = future.cancel(true)
                logger.debug(
                    LogCategory.Playback,
                    "Abandoned a pending session connection",
                    LogField.Public("wasPending", wasPending),
                )
            }
        }
    }

    private fun connectionFailed(failure: Throwable) {
        // The cause's class, not its message: a session-build failure can carry a component name and a
        // package, and PRODUCT_SPEC 14.5 keeps that out of a log the user might share.
        logger.warn(
            LogCategory.Playback,
            "Could not connect to the playback session",
            LogField.Public("cause", failure.javaClass.simpleName),
        )
    }
}
