package com.example.shelfplayer.playback

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Awaits a Media3 `ListenableFuture` from a coroutine.
 *
 * ### Why this is four lines here rather than a dependency
 *
 * `kotlinx-coroutines-guava` exists and does exactly this. It is not worth adding: it would be a new
 * pinned coordinate, a new SBOM entry and a new component for `scripts/vulnerability-scan.sh` to ask OSV
 * about, for one function whose whole content is `addListener`. `PlaybackService.future` already bridges
 * the other direction by hand for the same reason.
 *
 * ### The three ways a future can end, and all three are honoured
 *
 * A value resumes the caller. A **cancellation** cancels the caller, so a session that goes away while a
 * command is in flight unwinds as cancellation rather than as a failure — which is what
 * `kotlinx.coroutines` requires of any bridge, since its `CancellationException` *is*
 * `java.util.concurrent.CancellationException`. A **failure** rethrows the cause rather than the
 * `ExecutionException` wrapper, because the wrapper's class name is the one thing about it that says
 * nothing.
 *
 * Cancelling the caller cancels the future, so an abandoned command does not leave a listener attached to
 * something nobody is waiting for.
 *
 * `directExecutor` because the callback does nothing but resume a continuation: the coroutine machinery
 * then dispatches the resumption wherever the caller's context says, so there is no work to hand to a
 * thread pool here.
 *
 * `IgnoredReturnValue` is suppressed for the one call that has one: `Future.cancel` reports whether the
 * work was still running, and here it is not actionable — nobody is waiting on the future either way.
 */
@Suppress("IgnoredReturnValue")
internal suspend fun <T> ListenableFuture<T>.awaitOutcome(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (cancelled: CancellationException) {
                continuation.cancel(cancelled)
            } catch (failed: ExecutionException) {
                continuation.resumeWithException(failed.cause ?: failed)
            }
        },
        MoreExecutors.directExecutor(),
    )
    // `false` so a Media3 callback thread is never interrupted mid-dispatch.
    continuation.invokeOnCancellation { cancel(false) }
}
