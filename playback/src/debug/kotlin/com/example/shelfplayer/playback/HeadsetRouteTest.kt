package com.example.shelfplayer.playback

import android.content.Context
import androidx.annotation.MainThread
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.time.AppClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The real playback service owns the player. Closing the diagnostics screen does not end its observation. */
@Singleton
class HeadsetRouteTest @Inject internal constructor(
    @ApplicationContext context: Context,
    private val monitor: AudioTrackRouteMonitor,
    private val lab: AudioRouteLab,
    private val capture: UnifiedDiagnosticsCapture,
    private val clock: AppClock,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.Main) private val main: CoroutineDispatcher,
    @param:Dispatcher(ShelfDispatcher.Io) private val io: CoroutineDispatcher,
) {
    val journal = CarLabJournal.get(context)
    private var samples: NativeRouteSamples? = null
    private var poll: Job? = null

    @MainThread
    fun begin() {
        if (capture.clearing.value) return
        finish("next-test")
        capture.start()
        val id = journal.begin(clock.now())
        val run = NativeRouteSamples()
        samples = run
        monitor.testRouteObserver = { source, track, epoch ->
            run.observe(source, epoch.takeIf { track != null }, { NativeRoutePair.read(track) }, {
                monitor.isCurrent(track, epoch)
            })
        }
        monitor.sampleTestRoutes("run-start")
        flush()
        lab.onSystemTraceRow = { row, at -> journal.playbackMarker(row, at) }
        journal.onTraceIncident = { eventRun, cause ->
            scope.launch(io) { journal.incidentResult(eventRun, cause, CarLabSystemTrace.signal(incident = true)) }
        }
        scope.launch(io) { journal.triggerResult(id, CarLabSystemTrace.signal()) }
        poll = scope.launch(main) {
            var lastHeartbeat = clock.elapsed()
            while (isActive) {
                delay(POLL_MS)
                val now = clock.elapsed()
                val heartbeat = (now - lastHeartbeat).inWholeMilliseconds >= HEARTBEAT_MS
                monitor.sampleTestRoutes(if (heartbeat) "heartbeat" else "poll")
                if (heartbeat) lastHeartbeat = now
                flush()
            }
        }
    }

    @MainThread
    fun report(base: String): String {
        flush()
        return journal.attach(base)
    }

    @MainThread
    fun finish(reason: String = "finished") {
        if (samples == null) return
        poll?.cancel()
        poll = null
        monitor.sampleTestRoutes("run-end")
        monitor.testRouteObserver = null
        flush(close = true)
        samples = null
        lab.onSystemTraceRow = null
        journal.onTraceIncident = null
        journal.finish(reason, clock.now())
    }

    private fun flush(close: Boolean = false) {
        val batch = samples?.drain(close) ?: return
        if (batch.isNotEmpty()) journal.routes(journal.status.value.run, batch, clock.now())
    }

    private companion object {
        const val POLL_MS = 100L
        const val HEARTBEAT_MS = 5_000L
    }
}
