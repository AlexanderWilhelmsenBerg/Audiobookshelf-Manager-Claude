package com.example.shelfplayer.feature.book

import com.example.shelfplayer.core.model.AppResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * #202 — one Download tap at a time, acknowledged on the frame it happens.
 *
 * The request behind *Download* checks permission, the book's files and free space before it writes the
 * download, and the button used to look untouched for all of that. [isStarting] turns true **before** the
 * request is even launched, so the screen can answer the tap immediately, and it stays true until either
 * the request is refused — so the button reverts as the reason is shown — or, after a success, until the
 * screen has caught up with the written download, so the button never flickers back to *Download* in
 * between. A request that is cancelled or throws clears it too.
 *
 * Presentation only. It decides nothing about whether the download may start: that remains the request's.
 */
internal class DownloadStartTracker {

    private val starting = MutableStateFlow(false)

    /** Whether a tap's request is still on its way to a download the screen can show. */
    val isStarting: StateFlow<Boolean> = starting.asStateFlow()

    /**
     * Starts [request] for one tap, unless an earlier tap's request is still running.
     *
     * @param onResult receives the request's result before anything is cleared, so a refusal is reported
     *   as the button reverts rather than after it.
     * @param awaitShown suspends until the screen shows the download; called only after a success.
     * @return whether this tap started a request. A tap while one is running is absorbed: it would only
     *   repeat the request.
     */
    fun start(
        scope: CoroutineScope,
        request: suspend () -> AppResult<Unit>,
        onResult: (AppResult<Unit>) -> Unit,
        awaitShown: suspend () -> Unit,
    ): Boolean {
        if (!starting.compareAndSet(expect = false, update = true)) return false
        scope.launch {
            try {
                val result = request()
                onResult(result)
                if (result is AppResult.Success) awaitShown()
            } finally {
                starting.value = false
            }
        }
        return true
    }
}
