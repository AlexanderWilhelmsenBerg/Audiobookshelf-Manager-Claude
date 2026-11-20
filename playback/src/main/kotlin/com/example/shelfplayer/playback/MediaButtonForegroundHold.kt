package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.seconds

/** Owns the temporary foreground notification until the Media3 player takes over or a resume ends. */
@OptIn(UnstableApi::class)
internal class MediaButtonForegroundHold(
    private val scope: CoroutineScope,
    private val callbackExecutor: Executor,
    private val onAbandoned: (startId: Int) -> Unit,
) {
    class Attempt(val startId: Int, val resume: ListenableFuture<MediaSession.MediaItemsWithStartPosition>?) {
        var timeout: Job? = null
    }

    var current: Attempt? = null
        private set

    fun start(startId: Int) {
        replace(Attempt(startId, null))
    }

    fun watch(resume: ListenableFuture<MediaSession.MediaItemsWithStartPosition>) {
        val startId = current?.startId ?: return
        val attempt = Attempt(startId, resume)
        replace(attempt)
        Futures.addCallback(
            resume,
            object : FutureCallback<MediaSession.MediaItemsWithStartPosition> {
                override fun onSuccess(result: MediaSession.MediaItemsWithStartPosition) {
                    if (result.mediaItems.isEmpty()) abandon(attempt)
                }

                override fun onFailure(t: Throwable) = abandon(attempt)
            },
            callbackExecutor,
        )
    }

    fun handOff(attempt: Attempt) {
        if (current !== attempt) return
        current = null
        attempt.timeout?.cancel()
    }

    fun close() {
        val attempt = current ?: return
        current = null
        attempt.cancel()
    }

    private fun replace(attempt: Attempt) {
        val previous = current
        current = attempt
        previous?.cancel()
        attempt.timeout = scope.launch {
            delay(30.seconds)
            abandon(attempt)
        }
    }

    private fun abandon(attempt: Attempt) {
        if (current !== attempt) return
        close()
        onAbandoned(attempt.startId)
    }

    @Suppress("IgnoredReturnValue") // A resume that already completed has no pending work to cancel.
    private fun Attempt.cancel() {
        timeout?.cancel()
        resume?.cancel(false)
    }
}
