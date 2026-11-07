package com.example.shelfplayer.playback

import com.example.shelfplayer.core.common.time.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Read-only observation budget, not a car boundary, focus request or playback authorization. */
internal class CarConnectionRefreshWindow(private val clock: AppClock, private val refresh: (Int) -> Unit) {
    private var job: Job? = null

    fun onFocusLoss(scope: CoroutineScope, decision: CarArrivalResumeGate.Decision) {
        if (decision.phase == CarArrivalResumeGate.Phase.Arrival &&
            decision.status == CarArrivalResumeGate.Status.Armed &&
            decision.reason == CarArrivalResumeGate.Reason.FocusLossWaitingForBoundary
        ) {
            start(scope)
        }
    }

    fun start(scope: CoroutineScope) {
        // Retain the completed job until cancellation: duplicates cannot buy another observation window.
        if (job != null) return
        val startedAt = clock.elapsed()
        job = scope.launch(start = CoroutineStart.LAZY) {
            OFFSETS.forEachIndexed { index, offset ->
                delay((startedAt + offset.milliseconds - clock.elapsed()).coerceAtLeast(kotlin.time.Duration.ZERO))
                if (clock.elapsed() - startedAt > OFFSETS.last().milliseconds) return@launch
                refresh(index + 1)
            }
        }
        job?.start()
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private companion object {
        val OFFSETS = listOf(0L, 250L, 750L, 1500L, 2500L, 4000L, 6000L)
    }
}
