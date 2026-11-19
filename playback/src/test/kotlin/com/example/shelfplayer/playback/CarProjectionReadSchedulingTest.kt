package com.example.shelfplayer.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarProjectionReadSchedulingTest {
    private val logger = object : Logger {
        override fun log(event: LogEvent) = Unit
    }

    @Test
    fun `refreshes during a read coalesce and retain the initial observation before the arrival`() = runTest {
        val provider = Provider()
        ShadowContentResolver.registerProviderInternal(AndroidAutoProjectionMonitor.CAR_CONNECTION_AUTHORITY, provider)
        val monitor = AndroidAutoProjectionMonitor(
            RuntimeEnvironment.getApplication(),
            StandardTestDispatcher(testScheduler),
            logger,
            TestAppClock(),
        )
        val updates = mutableListOf<AndroidAutoProjectionMonitor.Update>()
        provider.duringFirstRead = {
            provider.state = 2
            monitor.refreshConnection(1)
            monitor.refreshConnection(2)
        }
        monitor.start(backgroundScope, updates::add)
        runCurrent()
        assertEquals(2, provider.reads, "multiple requests during one read produce only one follow-up")
        assertEquals(
            listOf(
                AndroidAutoProjectionMonitor.Update(null, AndroidAutoProjectionMonitor.State.NotConnected),
                AndroidAutoProjectionMonitor.Update(
                    AndroidAutoProjectionMonitor.State.NotConnected,
                    AndroidAutoProjectionMonitor.State.Projection,
                ),
            ),
            updates,
        )
        monitor.stop()
    }

    @Test
    fun `stopped observation cannot publish into a restarted monitor`() = runTest {
        val provider = Provider()
        ShadowContentResolver.registerProviderInternal(AndroidAutoProjectionMonitor.CAR_CONNECTION_AUTHORITY, provider)
        val monitor = AndroidAutoProjectionMonitor(
            RuntimeEnvironment.getApplication(),
            StandardTestDispatcher(testScheduler),
            logger,
            TestAppClock(),
        )
        val oldUpdates = mutableListOf<AndroidAutoProjectionMonitor.Update>()
        val newUpdates = mutableListOf<AndroidAutoProjectionMonitor.Update>()
        provider.duringFirstRead = {
            monitor.stop()
            provider.state = 2
            monitor.start(backgroundScope, newUpdates::add)
        }
        monitor.start(backgroundScope, oldUpdates::add)
        runCurrent()
        assertEquals(emptyList(), oldUpdates)
        assertEquals(
            listOf(AndroidAutoProjectionMonitor.Update(null, AndroidAutoProjectionMonitor.State.Projection)),
            newUpdates,
        )
        monitor.stop()
        monitor.refreshConnection(1)
        runCurrent()
        assertEquals(2, provider.reads)
    }

    private class Provider : ContentProvider() {
        var state = 0
        var reads = 0
        var duringFirstRead: () -> Unit = {}
        override fun onCreate() = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val captured = state
            reads++
            if (reads == 1) duringFirstRead()
            return MatrixCursor(arrayOf(AndroidAutoProjectionMonitor.CAR_CONNECTION_STATE)).apply {
                addRow(listOf(captured))
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("Read-only provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("Read-only provider")
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = error("Read-only provider")
    }
}
