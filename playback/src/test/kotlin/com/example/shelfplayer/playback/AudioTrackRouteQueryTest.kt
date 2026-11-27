package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.AudioDeviceInfoBuilder
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * PLAY-002 / ROUTE-002 — route proof belongs to the currently playing AudioTrack.
 * The shadow changes the real monitor's track lifecycle during a platform route read;
 * tests call currentRouteIds itself rather than reproducing its generation check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AudioTrackRouteQueryTest.QueryAudioTrack::class])
class AudioTrackRouteQueryTest {
    @Test
    fun `playing current track returns its exact registered route`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        fixture.observe(track)

        assertEquals(setOf(fixture.endpointId), fixture.monitor.currentRouteIds())
        assertEquals(1, track.queryShadow.routeReads)
    }

    @Test
    fun `replacement during route query rejects old evidence even for the same endpoint`() = runTest {
        val fixture = fixture()
        val first = fixture.track()
        val replacement = fixture.track()
        fixture.observe(first)
        first.queryShadow.onRouteRead = { fixture.observe(replacement) }

        assertNull(fixture.monitor.currentRouteIds(), "a successor cannot borrow the preceding track's route")
        assertEquals(1, first.queryShadow.routeReads)
        assertEquals(0, replacement.queryShadow.routeReads, "the in-flight query must not be retargeted")
        assertEquals(setOf(fixture.endpointId), fixture.monitor.currentRouteIds())
        assertEquals(1, replacement.queryShadow.routeReads)
    }

    @Test
    fun `release during route query rejects the captured track evidence`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        fixture.observe(track)
        track.queryShadow.onRouteRead = fixture::forget

        assertNull(fixture.monitor.currentRouteIds(), "a released track cannot prove a headset destination")
        assertEquals(1, track.queryShadow.routeReads)
        assertNull(fixture.monitor.currentRouteIds())
        assertEquals(1, track.queryShadow.routeReads, "no active track means no platform query")
    }

    @Test
    fun `reobserving the same track during a query still invalidates its old epoch`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        fixture.observe(track)
        track.queryShadow.onRouteRead = {
            track.queryShadow.onRouteRead = {}
            fixture.observe(track)
        }

        assertNull(fixture.monitor.currentRouteIds(), "matching track and endpoint identity cannot revive an old epoch")
        assertEquals(setOf(fixture.endpointId), fixture.monitor.currentRouteIds())
    }

    @Test
    fun `paused and uninitialized tracks provide no route proof`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        fixture.observe(track)
        track.queryShadow.trackPlayState = AudioTrack.PLAYSTATE_PAUSED
        assertNull(fixture.monitor.currentRouteIds())

        track.queryShadow.trackPlayState = AudioTrack.PLAYSTATE_PLAYING
        track.queryShadow.trackState = AudioTrack.STATE_UNINITIALIZED
        assertNull(fixture.monitor.currentRouteIds())
        assertEquals(0, track.queryShadow.routeReads, "unmeasurable tracks must not query a route")
    }

    @Test
    fun `state query failure provides no route proof`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        fixture.observe(track)
        track.queryShadow.onStateRead = { error("Track was released during state query") }

        assertNull(fixture.monitor.currentRouteIds())
        assertEquals(0, track.queryShadow.routeReads)
    }

    @Test
    fun `play state query failure provides no route proof`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        fixture.observe(track)
        track.queryShadow.onPlayStateRead = { error("Track was released during play-state query") }

        assertNull(fixture.monitor.currentRouteIds())
        assertEquals(0, track.queryShadow.routeReads)
    }

    @Test
    fun `route query failure provides no route proof`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        fixture.observe(track)
        track.queryShadow.onRouteRead = { error("Platform route is unavailable") }

        assertNull(fixture.monitor.currentRouteIds())
        assertEquals(1, track.queryShadow.routeReads)
    }

    @Test
    fun `RemoteSubmix is a type-only path and never a mapped physical destination`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        track.queryShadow.route = remoteSubmix()
        fixture.observe(track)

        val observed = assertNotNull(fixture.monitor.currentRouteObservation())

        assertEquals(AudioTrackPath.RemoteSubmix, observed.path)
        assertEquals(emptySet(), fixture.monitor.currentRouteIds(), "the path cannot invent an endpoint ID")
        assertEquals(observed, fixture.monitor.currentRouteObservation(), "one observer retains its existing epoch")
    }

    @Test
    fun `an unregistered endpoint remains unresolved rather than becoming a projection path`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        track.queryShadow.route = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_HEADSET).build()
        fixture.observe(track)

        assertEquals(AudioTrackPath.Unresolved, fixture.monitor.currentRouteObservation()?.path)
        assertEquals(emptySet(), fixture.monitor.currentRouteIds())
    }

    @Test
    fun `typed route proof carries the existing observer epoch across identical endpoint replacements`() = runTest {
        val fixture = fixture()
        val first = fixture.track()
        fixture.observe(first)
        val original = assertNotNull(fixture.monitor.currentRouteObservation())
        val successor = fixture.track()
        first.queryShadow.onRouteRead = { fixture.observe(successor) }

        assertNull(fixture.monitor.currentRouteObservation(), "a query cannot borrow the successor's epoch")
        val current = assertNotNull(fixture.monitor.currentRouteObservation())
        assertEquals(AudioTrackPath.Endpoint(fixture.endpointId), current.path)
        assertEquals(original.path, current.path)
        assertNotEquals(original.observerEpoch, current.observerEpoch)
    }

    @Test
    fun `the production classifier cannot collapse multiple sinks into a mapped or projection path`() = runTest {
        val fixture = fixture()
        val submix = remoteSubmix()
        // This Robolectric version cannot execute SDK36. Exercise the exact classifier called by its
        // multi-route branch; the platform getRoutedDevices adapter remains a device acceptance tier.
        val classifier = AudioTrackRouteMonitor::class.java.getDeclaredMethod("observedPath", List::class.java)
            .apply { isAccessible = true }
        assertEquals(AudioTrackPath.Multiple, classifier.invoke(fixture.monitor, listOf(fixture.device, submix)))
    }

    @Test
    fun `drive samples general capture and both car boundaries never poll native playback clocks`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        fixture.observe(track)
        val samples = NativeRouteSamples()
        val observation = fixture.monitor.observeRoutes(samples)
        observation.sample("drive-start")
        fixture.lab.start()
        for (boundary in AudioTimingBoundary.entries) fixture.monitor.sampleBoundary(boundary)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(java.time.Duration.ofSeconds(2))
        fixture.lab.clearAndAwait()
        looper.idleFor(java.time.Duration.ofSeconds(2))
        assertTrue(track.queryShadow.routeReads > 0)
        assertTrue(samples.snapshot().any { it.getJSONObject("actual").getString("status") == "available" })
        assertEquals(0, track.queryShadow.headReads, "diagnostics must never query the native playback head")
        assertEquals(0, track.queryShadow.timestampReads, "diagnostics must never query the native timestamp")
        observation.close()
        fixture.forget()
        fixture.lab.stop()
    }

    @Test
    fun `confirmed departure records only positions returned by the current output`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        val old = fixture.observe(track)
        val looper = shadowOf(Looper.getMainLooper())
        fixture.lab.start()
        looper.idleFor(java.time.Duration.ofSeconds(31))
        old.observe(125_000)
        fixture.monitor.sampleBoundary(AudioTimingBoundary.ProjectionDeparture)
        val alias = fixture.lab.deviceAlias(fixture.device.id)
        assertTrue(
            fixture.lab.state.value.entries.any {
                "edge=ProjectionDeparture" in it && "trackRoute=$alias:" in it && "outputPositionUs=125000" in it
            },
        )
        looper.idleFor(java.time.Duration.ofMillis(500))
        assertEquals(
            1,
            fixture.lab.state.value.entries.count {
                "track-timing" in it && "outputPositionUs=125000" in it
            },
        )
        val successor = fixture.observe(fixture.track())
        old.observe(999_000)
        assertNull(fixture.monitor.latestOutputPosition())
        successor.observe(600)
        assertEquals(600L, fixture.monitor.latestOutputPosition()?.outputPositionUs)

        fixture.lab.stop()
        fixture.monitor.sampleBoundary(AudioTimingBoundary.RouteSafetyPause)
        looper.idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(0, track.queryShadow.timestampReads)
    }

    @Test
    fun `daily drive exports fresh Media3 samples without polling the track during the departure tail`() = runTest {
        val f = fixture()
        val track = f.track()
        val output = f.observe(track)
        val drive = DriveRouteTrace(f.monitor, backgroundScope, f.clock) {}
        drive.boundary("drive-start", DriveRouteTrace.ARRIVAL_MS)
        drive.departure()
        val routeReads = track.queryShadow.routeReads
        runCurrent()
        f.clock.advanceBy(80.milliseconds)
        output.observe(280_000)
        advanceTimeBy(600)
        runCurrent()
        drive.close()
        val report = JSONObject().also(drive::appendTo)
        val clocks = report.getJSONArray("media3OutputClockSamples")
        assertEquals(1, clocks.length(), "an unchanged cached observation must only be exported once")
        assertEquals(280_000L, clocks.getJSONObject(0).getLong("outputPositionUs"))
        assertEquals(output.epoch, clocks.getJSONObject(0).getLong("trackEpoch"))
        assertEquals(routeReads, track.queryShadow.routeReads)
        assertEquals(0, track.queryShadow.headReads)
        assertEquals(0, track.queryShadow.timestampReads)
    }

    @Test
    fun `safety boundary records the raw active sink synchronously even after an inventory loss`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        fixture.observe(track)
        fixture.lab.start()
        fixture.registry.reconcile(emptyList())

        val reads = track.queryShadow.routeReads
        fixture.monitor.sampleBoundary(AudioTimingBoundary.RouteSafetyPause)
        track.queryShadow.trackPlayState = AudioTrack.PLAYSTATE_PAUSED

        assertEquals(reads + 1, track.queryShadow.routeReads)
        val alias = fixture.lab.deviceAlias(fixture.device.id)
        val row = fixture.lab.state.value.entries.single { "edge=RouteSafetyPause" in it }
        assertTrue("trackRoute=$alias:" in row)
        assertTrue("trackEpoch=" in row && "outputPositionUs=null" in row && "monotonicNs=" in row)

        fixture.lab.stop()
        fixture.monitor.sampleBoundary(AudioTimingBoundary.RouteSafetyPause)
        assertEquals(reads + 1, track.queryShadow.routeReads, "stopped capture must not query the sink")
    }

    @Test
    fun `inventory gap makes a still reported playing track unresolved rather than proving disconnection`() = runTest {
        val fixture = fixture()
        val track = fixture.track()
        fixture.observe(track)
        val before = assertNotNull(fixture.monitor.currentRouteObservation())
        assertEquals(AudioTrackPath.Endpoint(fixture.endpointId), before.path)

        fixture.registry.reconcile(emptyList())

        val during = assertNotNull(fixture.monitor.currentRouteObservation())
        assertEquals(before.observerEpoch, during.observerEpoch, "the AudioTrack has not been replaced")
        assertEquals(AudioTrack.PLAYSTATE_PLAYING, track.queryShadow.trackPlayState)
        assertEquals(fixture.device, track.queryShadow.route, "Android still reports the same sink in this fixture")
        assertEquals(AudioTrackPath.Unresolved, during.path, "missing inventory is not fresh endpoint proof")
        kotlin.test.assertTrue(fixture.monitor.currentRouteDiagnostic().contains("measured=AudioTrack"))
        assertEquals(AudioTrackPath.Unresolved, fixture.monitor.currentRouteObservation()?.path)
    }

    @Test
    fun `pre-pause diagnostic rejects a track replaced during its route query`() = runTest {
        val fixture = fixture()
        val old = fixture.track()
        fixture.observe(old)
        val successor = fixture.track()
        old.queryShadow.onRouteRead = { fixture.observe(successor) }
        assertEquals("track-replaced-during-query", fixture.monitor.currentRouteDiagnostic())
    }

    @Test
    fun `fresh exact Bluetooth identity survives inventory gap only as preservation evidence`() = runTest {
        val fixture = fixture(trusted = true)
        val track = fixture.track()
        fixture.observe(track)
        val live = assertNotNull(fixture.monitor.currentRouteObservation())
        fixture.registry.reconcile(emptyList())
        assertEquals(
            live.copy(path = AudioTrackPath.RetainedEndpoint(fixture.endpointId)),
            fixture.monitor.currentRouteObservation(),
        )
        assertEquals(emptySet(), fixture.monitor.currentRouteIds(), "retained identity cannot release a muted probe")
        track.queryShadow.trackPlayState = AudioTrack.PLAYSTATE_PAUSED
        assertNull(fixture.monitor.currentRouteObservation())
        track.queryShadow.trackPlayState = AudioTrack.PLAYSTATE_PLAYING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        assertEquals(AudioTrackPath.Unresolved, fixture.monitor.currentRouteObservation()?.path)
    }

    @Test
    fun `retained identity never bypasses track replacement and collision checks`() = runTest {
        val fixture = fixture(trusted = true)
        val old = fixture.track()
        fixture.observe(old)
        fixture.registry.reconcile(emptyList())
        old.queryShadow.onRouteRead = { fixture.observe(fixture.track()) }
        assertNull(fixture.monitor.currentRouteObservation())
        val observation = fixture.registry.observationOf(fixture.device)
        fixture.registry.reconcile(listOf(observation, observation.copy(platformId = observation.platformId + 1)))
        fixture.registry.reconcile(emptyList())
        assertEquals(AudioTrackPath.Unresolved, fixture.monitor.currentRouteObservation()?.path)
    }

    private fun remoteSubmix(): AudioDeviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_REMOTE_SUBMIX).build()
    } else {
        error("RemoteSubmix fixture requires SDK31 or later")
    }

    private fun TestScope.fixture(trusted: Boolean = false): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val clock = TestAppClock()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = object : Logger {
            override fun log(event: LogEvent) = Unit
        }
        val router = AudioOutputRouter(
            context,
            logger,
            backgroundScope,
            dispatcher,
            volumeEffects = volumeEffectsForTest(dispatcher, clock),
        )
        val lab = AudioRouteLab(context, EventLog(clock), clock, router, backgroundScope, dispatcher)
        val device = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build()
        if (trusted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                shadowOf(context).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            val port = org.robolectric.util.ReflectionHelpers.getField<Any>(device, "mPort")
            org.robolectric.util.ReflectionHelpers.setField(port, "mAddress", "10:AB:33:44:55:66")
        }
        val registry = LiveOutputEndpointRegistry()
        val endpoint = registry.reconcile(
            listOf(registry.observationOf(device)),
        ).single()
        return Fixture(AudioTrackRouteMonitor(context, lab, clock, registry), device, endpoint.id, lab, registry, clock)
    }

    private data class Fixture(
        val monitor: AudioTrackRouteMonitor,
        val device: AudioDeviceInfo,
        val endpointId: String,
        val lab: AudioRouteLab,
        val registry: LiveOutputEndpointRegistry,
        val clock: TestAppClock,
    ) {
        fun track(): AudioTrack = Shadow.newInstanceOf(AudioTrack::class.java).also { it.queryShadow.route = device }

        // These are the exact seams called by the production Media3 output provider on replacement/release.
        fun observe(track: AudioTrack): ObservedOutputClock =
            AudioTrackRouteMonitor::class.java.getDeclaredMethod("observe", AudioTrack::class.java)
                .apply { isAccessible = true }.invoke(monitor, track) as ObservedOutputClock

        fun forget() {
            AudioTrackRouteMonitor::class.java.getDeclaredMethod("forget").apply {
                isAccessible = true
                invoke(monitor)
            }
        }
    }

    @Implements(AudioTrack::class)
    @Suppress("UNUSED_PARAMETER")
    class QueryAudioTrack {
        var trackState = AudioTrack.STATE_INITIALIZED
        var trackPlayState = AudioTrack.PLAYSTATE_PLAYING
        var route: AudioDeviceInfo? = null
        var routeReads = 0
        var timestampReads = 0
        var headReads = 0

        @Implementation
        fun getPlaybackHeadPosition(): Int {
            headReads++
            return 0
        }

        @Implementation
        fun getTimestamp(timestamp: AudioTimestamp): Boolean {
            timestampReads++
            return false
        }
        var onStateRead: () -> Unit = {}
        var onPlayStateRead: () -> Unit = {}
        var onRouteRead: () -> Unit = {}

        @Implementation
        fun getState(): Int {
            onStateRead()
            return trackState
        }

        @Implementation
        fun getPlayState(): Int {
            onPlayStateRead()
            return trackPlayState
        }

        @Implementation
        fun getRoutedDevice(): AudioDeviceInfo? {
            routeReads++
            onRouteRead()
            return route
        }

        @Implementation
        fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener, handler: Handler?) = Unit

        @Implementation
        fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener) = Unit
    }
}

private val AudioTrack.queryShadow: AudioTrackRouteQueryTest.QueryAudioTrack
    get() = Shadow.extract(this)
