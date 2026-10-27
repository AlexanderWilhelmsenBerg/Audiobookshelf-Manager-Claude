package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.resultOf
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Media3's future boundary. Completion shares the application thread with final queue authorization;
 * no background-dispatcher gap may publish an old accepted queue after a newer controller command.
 * The application scope lets a browse answer complete during service teardown. Playback claims still
 * check the service lifetime themselves. Cancellation in either direction always terminates both sides.
 */
internal class SessionCallbackFutures(
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher,
    private val logger: Logger,
) {
    private val playbackDelivery = ThreadLocal<Boolean>()

    /** Media3 must not apply fallback prepare/Play from a rejected cold-resumption future. */
    fun playerEffectsAllowed(): Boolean = playbackDelivery.get() != false

    /** A permitted framework resumption delivery is passive, only for the current call stack. */
    fun isPlaybackResumptionDelivery(): Boolean = playbackDelivery.get() == true

    fun <T> submitPlayback(
        authorized: () -> Boolean,
        playable: (T) -> Boolean,
        block: suspend () -> T,
    ): ListenableFuture<T> {
        var successfulPlayable = false
        val answer = submit { block().also { successfulPlayable = playable(it) } }
        return object : ListenableFuture<T> by answer {
            override fun addListener(listener: Runnable, executor: java.util.concurrent.Executor) {
                answer.addListener(
                    {
                        // Capture once: an accepted compound install/prepare/Play advances the existing
                        // command generation itself. Re-reading it between those effects would reject Play.
                        val previous = playbackDelivery.get()
                        playbackDelivery.set(
                            previous != false && !answer.isCancelled && successfulPlayable && authorized(),
                        )
                        try {
                            listener.run()
                        } finally {
                            if (previous == null) playbackDelivery.remove() else playbackDelivery.set(previous)
                        }
                    },
                    executor,
                )
            }
        }
    }

    fun <T> submit(block: suspend () -> T): ListenableFuture<T> {
        val answer = SettableFuture.create<T>()
        val job = scope.launch(mainDispatcher) {
            when (val outcome = resultOf { block() }) {
                is AppResult.Success -> answer.set(outcome.value)

                is AppResult.Failure -> {
                    // The exception message may contain a private URL/title. Only its class is public.
                    val cause = (outcome.error as? AppError.Unknown)?.cause
                    logger.warn(
                        LogCategory.Playback,
                        "A browse request failed",
                        LogField.Public("error", outcome.error.code),
                        LogField.Public("thrown", cause?.let { it::class.java.simpleName } ?: "none"),
                    )
                    answer.setException(IllegalStateException(outcome.error.code))
                }
            }
        }
        answer.addListener({ if (answer.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        job.invokeOnCompletion { if (!answer.isDone) answer.cancel(false) }
        return answer
    }
}
