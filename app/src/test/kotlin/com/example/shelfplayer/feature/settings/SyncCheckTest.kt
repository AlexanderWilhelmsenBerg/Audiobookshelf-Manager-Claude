package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.R
import com.example.shelfplayer.core.model.playback.ClockSkew
import com.example.shelfplayer.core.model.playback.SessionSyncDiagnostics
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-005 / SET-002 — the wave-3 checklist's verdicts.
 *
 * These matter because a checklist that ticks itself optimistically is worse than no checklist: it would report
 * a drained queue on a build that never uploaded anything. Each verdict names its evidence, and these tests are
 * what hold it to that.
 */
class SyncCheckTest {

    private fun stateOf(labelRes: Int, sync: SessionSyncDiagnostics): SyncCheckState =
        checksFor(sync).single { it.labelRes == labelRes }.state

    @Test
    fun `nothing is marked seen before anything has happened`() {
        val checks = checksFor(SessionSyncDiagnostics())

        assertEquals(emptyList(), checks.filter { it.state == SyncCheckState.Seen })
    }

    /**
     * A drained queue is "something accepted **and** nothing waiting".
     *
     * Either half alone is not it: an accepted session with a row still queued is a queue mid-drain, and an
     * empty queue with nothing ever accepted is a build that has not played anything.
     */
    @Test
    fun `the queue counts as drained only when something was accepted and nothing waits`() {
        assertEquals(
            SyncCheckState.Seen,
            stateOf(
                R.string.settings_check_queue_drains,
                SessionSyncDiagnostics(sessionsSynced = 1, sessionsPending = 0),
            ),
        )
        assertEquals(
            SyncCheckState.Waiting,
            stateOf(
                R.string.settings_check_queue_drains,
                SessionSyncDiagnostics(sessionsSynced = 1, sessionsPending = 1),
            ),
        )
        assertEquals(
            SyncCheckState.Waiting,
            stateOf(
                R.string.settings_check_queue_drains,
                SessionSyncDiagnostics(sessionsSynced = 0, sessionsPending = 0),
            ),
        )
    }

    @Test
    fun `the server accepting is seen once a position has landed`() {
        assertEquals(
            SyncCheckState.Seen,
            stateOf(
                R.string.settings_check_server_accepts,
                SessionSyncDiagnostics(lastSyncedAt = Instant.ofEpochSecond(1_000)),
            ),
        )
    }

    /** A queued row is what an offline session looks like from here. */
    @Test
    fun `a queued session marks the offline check`() {
        assertEquals(
            SyncCheckState.Seen,
            stateOf(R.string.settings_check_offline_queue, SessionSyncDiagnostics(sessionsPending = 2)),
        )
    }

    /** An upload that succeeded but left an error recorded is not a clean drain. */
    @Test
    fun `a recorded failure keeps the drain check open`() {
        assertEquals(
            SyncCheckState.Waiting,
            stateOf(
                R.string.settings_check_offline_drains,
                SessionSyncDiagnostics(sessionsSynced = 1, lastFailureCode = "network"),
            ),
        )
        assertEquals(
            SyncCheckState.Seen,
            stateOf(
                R.string.settings_check_offline_drains,
                SessionSyncDiagnostics(sessionsSynced = 1, lastFailureCode = null),
            ),
        )
    }

    /**
     * The three checks no reading on this device can settle stay marked as needing one.
     *
     * A killed process, a deliberate rewind the *server* has to have kept, and a second device are all outside
     * what this process can observe, and a checklist that cannot tell "not done" from "not checkable here"
     * reads as failing forever.
     */
    @Test
    fun `the checks that need hardware say so rather than sitting unticked`() {
        val complete = SessionSyncDiagnostics(
            sessionsRecorded = 4,
            sessionsPending = 0,
            sessionsSynced = 4,
            lastSyncedAt = Instant.ofEpochSecond(1_000),
        )

        val manual = checksFor(complete).filter { it.state == SyncCheckState.NeedsDevice }.map { it.labelRes }

        assertEquals(
            listOf(
                R.string.settings_check_kill,
                R.string.settings_check_rewind,
                R.string.settings_check_clock,
                R.string.settings_check_second_device,
            ),
            manual,
        )
    }

    /**
     * The clock check is the one manual case the app *can* settle, and only in one direction.
     *
     * Seeing significant skew proves the detection works. Not seeing it proves nothing — the tester may simply
     * not have moved the clock yet — so the absence stays "needs a device" rather than becoming a failure.
     */
    @Test
    fun `significant skew marks the clock check and small skew does not`() {
        assertEquals(
            SyncCheckState.Seen,
            stateOf(
                R.string.settings_check_clock,
                SessionSyncDiagnostics(clockSkew = ClockSkew(6.minutes, Instant.ofEpochSecond(0))),
            ),
        )
        assertEquals(
            SyncCheckState.NeedsDevice,
            stateOf(
                R.string.settings_check_clock,
                SessionSyncDiagnostics(clockSkew = ClockSkew(2.seconds, Instant.ofEpochSecond(0))),
            ),
        )
    }

    /** Every check has a distinct label, or `items(key = …)` would crash the list. */
    @Test
    fun `the checks are keyed uniquely`() {
        val checks = checksFor(SessionSyncDiagnostics())

        assertEquals(checks.size, checks.map { it.labelRes }.distinct().size)
    }
}
