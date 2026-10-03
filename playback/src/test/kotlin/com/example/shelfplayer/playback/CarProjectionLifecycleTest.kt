package com.example.shelfplayer.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.lang.reflect.Proxy
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * PLAY-002 / ROUTE-002 / GitHub #128 (historical #36).
 *
 * Runs the real projection provider reader and broadcast receiver into PlaybackService's production callback.
 * The framework/Hilt onCreate and audible routing remain device acceptance: this fixture injects only the
 * callback's dependencies and never manufactures a playing Media3 service or claims to observe an audio sink.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarProjectionLifecycleTest {
    @Test
    fun `positive projection followed by unknown then disconnected completes departure once`() = runTest {
        fixture().use { car ->
            runCurrent()
            car.armDeparture()

            car.read(null)
            runCurrent()
            assertTrue(car.connections.isConnected())
            assertTrue(car.ownsLifecycle())
            assertTrue(car.isEstablished())
            assertEquals(0, car.departureDecisions().size)

            car.read(0)
            runCurrent()

            assertFalse(car.connections.isConnected(), "the confirmed exit retires every stale controller")
            assertFalse(car.ownsLifecycle())
            assertFalse(car.isEstablished())
            assertEquals(listOf("Ready"), car.departureDecisions().map { it.public("status") })
            assertEquals("Departure", car.departureDecisions().single().public("phase"))

            car.read(0)
            runCurrent()
            car.disconnectController()
            car.disconnectController()
            runCurrent()
            assertFalse(car.connections.isConnected())
            assertEquals(1, car.departureDecisions().size, "duplicate reads and late controllers cannot depart twice")
        }
    }

    @Test
    fun `unknown then positive recovery is not another arrival for an established projection`() = runTest {
        fixture().use { car ->
            runCurrent()
            car.armDeparture()
            car.read(null)
            runCurrent()

            car.read(2)
            runCurrent()

            assertTrue(car.connections.isConnected())
            assertTrue(car.ownsLifecycle())
            assertTrue(car.isEstablished())
            assertEquals(0, car.arrivalDecisions().size, "a recovered provider read is not a second ignition")
        }
    }

    @Test
    fun `unknown alone never ends a positively observed projection`() = runTest {
        fixture().use { car ->
            runCurrent()
            car.armDeparture()
            car.read(null)
            runCurrent()
            car.disconnectController()
            car.disconnectController()
            runCurrent()

            assertTrue(car.ownsLifecycle(), "legacy controller loss cannot turn Unknown into a physical exit")
            assertTrue(car.isEstablished())
            assertEquals(0, car.departureDecisions().size)
        }
    }

    @Test
    fun `unknown with no earlier positive projection cannot manufacture departure`() = runTest {
        fixture(initialState = null).use { car ->
            runCurrent()
            assertFalse(car.ownsLifecycle())

            car.read(0)
            runCurrent()

            assertTrue(car.connections.isConnected(), "no positive projection edge means no projection cleanup")
            assertFalse(car.ownsLifecycle())
            assertEquals(0, car.departureDecisions().size)

            car.disconnectController()
            car.disconnectController()
            runCurrent()
            assertFalse(car.connections.isConnected())
            assertEquals(1, car.legacyDepartureDecisions().size, "the controller fallback remains reachable")
        }
    }

    @Test
    fun `confirmed exit after unknown cannot resume a deliberately cancelled transition`() = runTest {
        fixture().use { car ->
            runCurrent()
            car.armDeparture()
            car.continuity.cancelAll()
            car.read(null)
            runCurrent()
            car.read(0)
            runCurrent()

            assertFalse(car.ownsLifecycle())
            assertFalse(car.connections.isConnected())
            assertEquals(listOf("Rejected"), car.departureDecisions().map { it.public("status") })
            assertEquals("NoPlayingCarHeadset", car.departureDecisions().single().public("reason"))
            assertFalse(car.events.any { it.message == "Car lifecycle continuity issued Play" })
        }
    }

    private fun TestScope.fixture(initialState: Int? = 2): Fixture =
        Fixture(backgroundScope, StandardTestDispatcher(testScheduler), initialState)

    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher, initialState: Int?) : AutoCloseable {
        val events = mutableListOf<LogEvent>()
        private val logger = object : Logger {
            override fun log(event: LogEvent) {
                events += event
            }
        }
        private val context = RuntimeEnvironment.getApplication()
        private val clock = TestAppClock()
        val connections = CarConnections(clock).apply {
            onConnected()
            onConnected()
        }
        private val service = PlaybackService()
        private val provider = ProjectionProvider().apply { state = initialState }
        private val monitor = AndroidAutoProjectionMonitor(context, dispatcher, logger, clock)
        val continuity: CarArrivalResumeGate get() = service.field("carContinuity")

        init {
            val sync = SessionSyncCoordinator(
                repository = unused<SessionSyncRepository>(),
                baseline = ResumeBaseline(),
                clock = clock,
                logger = logger,
                applicationScope = scope,
                mainDispatcher = dispatcher,
            )
            service.audioOutputs = AudioOutputRouter(context, logger, scope, dispatcher)
            service.clock = clock
            service.logger = logger
            service.carConnections = connections
            service.sleepTimer = SleepTimerController(
                repository = proxy<SleepTimerRepository> { name ->
                    when (name) {
                        "observeSettings" -> flowOf(SleepTimerSettings.Default)
                        else -> error("Unexpected sleep repository call: $name")
                    }
                },
                shakes = proxy<ShakeSource> { name ->
                    when (name) {
                        "isSensing" -> false
                        else -> error("Unexpected shake call: $name")
                    }
                },
                sessionSync = sync,
                history = unused<PlaybackHistoryRepository>(),
                clock = clock,
                zoneProvider = object : LocalZoneProvider {
                    override fun current(): ZoneId = ZoneId.of("UTC")
                },
                logger = logger,
                applicationScope = scope,
                mainDispatcher = dispatcher,
            )
            service.setField("scope", scope)
            ShadowContentResolver.registerProviderInternal(
                AndroidAutoProjectionMonitor.CAR_CONNECTION_AUTHORITY,
                provider,
            )
            monitor.start(scope) { update -> service.invoke("onCarProjectionUpdate", update) }
        }

        fun read(state: Int?) {
            provider.state = state
            context.sendBroadcast(Intent(AndroidAutoProjectionMonitor.ACTION_CAR_CONNECTION_UPDATED))
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun armDeparture() {
            val owner: RouteHeardOwnership = service.field("routeOwnership")
            val buds = AudioOutput(
                id = "bluetooth:fixture",
                displayName = "Fixture headset",
                kind = DeviceKind.Bluetooth,
                role = AudioOutputRole.Headset,
            )
            owner.onBookChanged(hasBook = true)
            owner.onPlaybackObserved(listOf(buds.copy(isActive = true)))
            continuity.observePlayingHeadset(owner.heardRoute, buds.id, owner.currentGeneration, 0)
            val decision = continuity.onAudioFocusLoss(
                at = clock.elapsed(),
                heardRoute = owner.heardRoute,
                headsetId = buds.id,
                currentGeneration = owner.currentGeneration,
                explicitSelectionSequence = 0,
                carConnected = true,
            )
            assertEquals(CarArrivalResumeGate.Status.Armed, decision.status)
            clock.advanceBy(1.seconds)
        }

        fun disconnectController() =
            service.invoke("onCarControllerDisconnected", "com.google.android.projection.gearhead")

        fun ownsLifecycle(): Boolean = service.field("projectionOwnsCarLifecycle")

        fun isEstablished(): Boolean = service.field("carContinuitySessionEstablished")

        fun departureDecisions(): List<LogEvent> = decisions("projection-disconnect")

        fun legacyDepartureDecisions(): List<LogEvent> = decisions("final-car-disconnect")

        fun arrivalDecisions(): List<LogEvent> = decisions("projection-connect")

        private fun decisions(source: String): List<LogEvent> = events.filter {
            it.message == "Car lifecycle continuity decision" && it.public("source") == source
        }

        override fun close() = monitor.stop()
    }

    private class ProjectionProvider : ContentProvider() {
        var state: Int? = null

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = state?.let { value ->
            MatrixCursor(arrayOf(AndroidAutoProjectionMonitor.CAR_CONNECTION_STATE)).apply { addRow(arrayOf(value)) }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri = error("Read-only projection fixture")

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Read-only projection fixture")

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = error("Read-only projection fixture")
    }

    private companion object {
        fun LogEvent.public(key: String): String? =
            fields.filterIsInstance<LogField.Public>().firstOrNull { it.key == key }?.value

        inline fun <reified T> PlaybackService.field(name: String): T =
            PlaybackService::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(this) as T

        fun PlaybackService.setField(name: String, value: Any) {
            PlaybackService::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(this, value)
        }

        fun PlaybackService.invoke(name: String, argument: Any) {
            PlaybackService::class.java.getDeclaredMethod(name, argument.javaClass)
                .also { it.isAccessible = true }.invoke(this, argument)
        }

        inline fun <reified T : Any> unused(): T = proxy { error("Unexpected ${T::class.simpleName} call: $it") }

        inline fun <reified T : Any> proxy(crossinline answer: (String) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                answer(method.name.substringBefore('-').removePrefix("get"))
            } as T
    }
}
