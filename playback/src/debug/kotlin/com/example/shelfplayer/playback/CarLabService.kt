package com.example.shelfplayer.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.time.AppClock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** A private, debug-only foreground service. Sample playback never opens or checkpoints a book. */
@AndroidEntryPoint
class CarLabService : Service() {
    @Inject
    @Dispatcher(ShelfDispatcher.Io)
    lateinit var io: CoroutineDispatcher

    @Inject
    @Dispatcher(ShelfDispatcher.Main)
    lateinit var main: CoroutineDispatcher

    @Inject lateinit var capture: UnifiedDiagnosticsCapture

    @Inject lateinit var logger: Logger

    @Inject lateinit var clock: AppClock

    private val scope by lazy { CoroutineScope(SupervisorJob() + main) }
    private val journal by lazy { CarLabJournal.get(this) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val projection by lazy { AndroidAutoProjectionMonitor(this, io, logger, clock) }
    private var job: Job? = null
    private var engine: CarLabEngine? = null
    private var focus: AudioFocusRequest? = null
    private var target: AudioDeviceInfo? = null
    private var run = 0
    private var observing = false
    private var pausedByFocus = false
    private var sampleStopped = false
    private var devices: AudioDeviceCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val expected = intent?.getIntExtra("run", 0) ?: 0
        val state = journal.status.value
        showNotification(state)
        if (intent?.action == STOP) {
            if (expected == state.run) end(this, "finished")
        } else if (expected == state.run && state.case != null) {
            startRun(expected, state.case)
        }
        if (journal.status.value.case == null) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun startRun(id: Int, case: CarLabCase) {
        retire()
        run = id
        closeRun = {
            retire()
            stopSelf()
        }
        capture.start()
        projection.start(scope) { update ->
            if (owns(id)) journal.record("projection", JSONObject().put("state", update.current.name))
        }
        observing = true
        if (case != CarLabCase.Book) {
            if (BookPlayObservation.current?.invoke()?.playWhenReady == true) {
                end(this, "pause-book-first")
                return
            }
            target = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter(::isHeadset).singleOrNull()
            if (target == null) {
                end(this, "connect-one-headset-first")
                return
            }
            watchDevices(id)
            BookPlayObservation.onPlay = { if (owns(id)) stopSample("book-play-requested") }
        }
        job = scope.launch { monitorRun(id, case) }
    }

    private suspend fun monitorRun(id: Int, case: CarLabCase) {
        try {
            if (case != CarLabCase.Book) startSample(id, case)
            if (owns(id)) observePlayback(id, case)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            engineFailed(id, error)
        } catch (error: IllegalStateException) {
            engineFailed(id, error)
        } catch (error: IllegalArgumentException) {
            engineFailed(id, error)
        } catch (error: UnsupportedOperationException) {
            engineFailed(id, error)
        } catch (error: SecurityException) {
            engineFailed(id, error)
        } catch (error: LinkageError) {
            engineFailed(id, error)
        }
    }

    private fun engineFailed(id: Int, error: Throwable) {
        if (owns(id)) end(this, "engine-error-" + error.javaClass.simpleName)
    }

    private fun fixture(): File = synchronized(fixtureLock) {
        val file = File(cacheDir, "car-lab-count-v1.m4a")
        if (!file.exists()) {
            val temp = File.createTempFile("car-count-", ".m4a", cacheDir)
            try {
                resources.openRawResource(R.raw.car_lab_count).use { source ->
                    temp.outputStream().use { output -> source.copyTo(output) }
                }
                check(temp.renameTo(file))
            } finally {
                temp.delete()
            }
        }
        file
    }

    private suspend fun startSample(id: Int, case: CarLabCase) {
        val file = withContext(io) { fixture() }
        val pcm = withContext(io) {
            if (case == CarLabCase.Pcm) LabPcm.decode(file) else null
        }
        if (!owns(id) || sampleStopped) return
        val failed = {
            Handler(Looper.getMainLooper()).post { if (owns(id)) stopSample("engine-error") }
            Unit
        }
        engine = when (case) {
            CarLabCase.Exo -> LabExo(this, file, failed)

            CarLabCase.Vlc -> LabVlc(this, file, failed)

            CarLabCase.Pcm -> LabAudioTrack(requireNotNull(pcm))

            CarLabCase.Platform, CarLabCase.Preferred, CarLabCase.Cooperate ->
                LabMediaPlayer(file, target.takeIf { case.pin }, failed)

            CarLabCase.Book -> error("Book uses the production session")
        }
        if (!targetPresent()) {
            stopSample("starting-headset-removed")
            return
        }
        if (!requestFocus(id, case)) {
            end(this, "focus-denied")
            return
        }
        engine?.play()
        journal.record("sample-started")
    }

    private fun requestFocus(id: Int, case: CarLabCase): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(labAudioAttributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (owns(id) && !sampleStopped) {
                    journal.record("audio-focus", JSONObject().put("change", change))
                    if (case == CarLabCase.Cooperate) handleFocus(change)
                }
            }, Handler(Looper.getMainLooper())).build()
        focus = request
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun handleFocus(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stopSample("permanent-focus-loss")

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                engine?.pause()
                pausedByFocus = true
            }

            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus && targetPresent()) {
                pausedByFocus = false
                engine?.play()
            }
        }
    }

    private suspend fun observePlayback(id: Int, case: CarLabCase) {
        var heartbeat = 0L
        var previous: LabPositionSample? = null
        while (owns(id)) {
            val sample = sample(case)
            if (sample.at >= heartbeat || sample.changedSince(previous)) {
                journal.record("playback-sample", sample.json())
                heartbeat = sample.at + HEARTBEAT_MS
            }
            if (engine != null && (sample.route == "other-output" || !targetPresent())) {
                stopSample(if (sample.route == "other-output") "output-moved" else "starting-headset-removed")
            }
            previous = sample
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    private fun sample(case: CarLabCase): LabPositionSample {
        val book = if (case == CarLabCase.Book) BookPlayObservation.current?.invoke() else null
        val device = engine?.routed
        val route = when {
            device == null -> "unreported"
            device.id == target?.id -> "starting-headset"
            else -> "other-output"
        }
        return LabPositionSample(
            SystemClock.elapsedRealtime(),
            engine?.positionMs ?: book?.currentPosition,
            engine?.playing ?: (book?.isPlaying == true),
            route,
        )
    }

    private fun targetPresent() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.id == target?.id }

    private fun watchDevices(id: Int) {
        devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                if (!owns(id)) return
                val removedTarget = removedDevices.any { it.id == target?.id }
                journal.record("outputs-removed", JSONObject().put("startingHeadsetRemoved", removedTarget))
                if (removedTarget) stopSample("starting-headset-removed")
            }

            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                if (owns(id)) {
                    journal.record(
                        "outputs-added",
                        JSONObject().put(
                            "count",
                            addedDevices.count {
                                it.isSink
                            },
                        ),
                    )
                }
            }
        }.also { audio.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }
    }

    private fun owns(id: Int) = run == id && journal.status.value.run == id && journal.status.value.case != null

    private fun retire() {
        run = 0
        job?.cancel()
        job = null
        BookPlayObservation.onPlay = null
        devices?.let(audio::unregisterAudioDeviceCallback)
        devices = null
        if (observing) projection.stop()
        observing = false
        releaseEngine()
        target = null
        pausedByFocus = false
        sampleStopped = false
    }

    private fun stopSample(reason: String) {
        if (sampleStopped) return
        sampleStopped = true
        journal.sampleStopped(reason)
        releaseEngine()
    }

    private fun releaseEngine() {
        engine?.close()
        engine = null
        focus?.let(audio::abandonAudioFocusRequest)
        focus = null
    }

    override fun onDestroy() {
        if (owns(run)) journal.finish("service-stopped")
        retire()
        closeRun = null
        scope.cancel()
        super.onDestroy()
    }

    private fun showNotification(state: CarLabStatus) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Car playback tests", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this,
            state.run,
            Intent(this, CarLabService::class.java)
                .setAction(STOP).putExtra("run", state.run),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BookWave car test #" + state.run)
            .setContentText(state.case?.label).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Finish run", stop).build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 250L
        private const val HEARTBEAT_MS = 5_000L
        private const val NOTIFICATION_ID = 9831
        private const val CHANNEL = "car-playback-tests"
        private const val STOP = "finish-car-test"
        private var closeRun: (() -> Unit)? = null
        private val fixtureLock = Any()

        fun begin(context: Context, case: CarLabCase) {
            end(context, "next-test")
            val journal = CarLabJournal.get(context)
            val id = journal.begin(case)
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CarLabService::class.java).putExtra("run", id),
                )
            } catch (error: IllegalStateException) {
                journal.finish("service-start-failed-" + error.javaClass.simpleName)
            } catch (error: SecurityException) {
                journal.finish("service-start-failed-" + error.javaClass.simpleName)
            }
        }

        fun end(context: Context, reason: String = "finished") {
            CarLabJournal.get(context).finish(reason)
            closeRun?.invoke()
            closeRun = null
        }

        private fun isHeadset(device: AudioDeviceInfo): Boolean =
            OutputDevices.outputOf(device.type, productName = null)?.isHeadsetCandidate == true
    }
}
