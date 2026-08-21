package com.example.shelfplayer.feature.settings

import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LoggedEvent
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.playback.PlaybackMetrics
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.domain.usecase.ServerDiagnostics
import org.junit.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC 14.5 — "keep private self-hosted data out of logs and reports".
 *
 * **This is the file the debug console exists to be checked by.** The report is built from live domain
 * state, which — unlike the event log — has passed through no redactor. Every private value that state can
 * hold is planted here, and the test fails if any of them appears in the output.
 *
 * If somebody later adds a field to the report, this test is what tells them whether they were allowed to.
 */
class DiagnosticsReportTest {

    /** Values that must never reach a report. Each one is planted in the state the report is built from. */
    private val secrets = listOf(
        "books.alexanders-house.example",
        "Alexander's Private Shelf",
        "The Salt Harbour",
        "Alex's AirPods Pro",
        "eyJhbGciOiJIUzI1NiJ9.super-secret-token",
    )

    @Test
    fun `no private value reaches the report`() {
        val report = DiagnosticsReport.of(
            appVersion = "0.9.9 (debug)",
            state = state(),
            metrics = PlaybackMetrics(rebuffers = 3, startupsMeasured = 9),
            events = emptyList(),
            at = Instant.ofEpochMilli(0),
        )

        secrets.forEach { secret ->
            assertFalse(report.contains(secret), "the report leaked: $secret")
        }
    }

    /**
     * The hostname is the one a careless reader would think is fine to include, because it is *useful*.
     *
     * It is not fine. PRODUCT_SPEC 14.5 treats a self-hosted address as private, and the version and the
     * capability set answer every question a supporter would have asked it for.
     */
    @Test
    fun `the server is described by version and capabilities, never by address`() {
        val report = DiagnosticsReport.of("0.9.9", state(), PlaybackMetrics.Empty, emptyList(), Instant.EPOCH)

        assertFalse(report.contains("alexanders-house"))
        assertTrue(report.contains("2.36.0"))
        assertTrue(report.contains(ServerCapability.Websocket.name))
    }

    /** Libraries are counted, never named. */
    @Test
    fun `libraries appear as a count`() {
        val report = DiagnosticsReport.of("0.9.9", state(), PlaybackMetrics.Empty, emptyList(), Instant.EPOCH)

        assertTrue(report.contains("visible: 1"))
        assertFalse(report.contains("Private Shelf"))
    }

    /**
     * The log lines *are* included verbatim, and that is correct: nothing reaches the log except through a
     * `LogField`, so `Redactor` has already decided what each line may say. This asserts the inclusion so a
     * future change that drops them is deliberate rather than accidental — the log is the most useful part.
     */
    @Test
    fun `event log lines are carried through`() {
        val events = listOf(
            LoggedEvent(Instant.EPOCH, LogLevel.Warn, "Sync", "a handshake failed"),
        )

        val report = DiagnosticsReport.of("0.9.9", state(), PlaybackMetrics.Empty, events, Instant.EPOCH)

        assertTrue(report.contains("a handshake failed"))
        assertTrue(report.contains("[events] 1"))
    }

    /** The counts a supporter actually asks for, present and labelled. */
    @Test
    fun `the storage and session counters are reported`() {
        val report = DiagnosticsReport.of("0.9.9", state(), PlaybackMetrics.Empty, emptyList(), Instant.EPOCH)

        assertTrue(report.contains("books: 412"))
        assertTrue(report.contains("progress: 88 (unsynced 4)"))
    }

    private fun state() = SettingsUiState(
        libraries = listOf(
            Library(
                serverId = ServerId("srv-1"),
                id = LibraryId("lib-1"),
                // Planted: a library name is private (PRODUCT_SPEC 14.5).
                name = "Alexander's Private Shelf",
                kind = LibraryKind.Book,
                displayOrder = 0,
                bookCount = 412,
                remoteUpdatedAt = null,
                lastFetchedAt = Instant.EPOCH,
            ),
        ),
        server = ServerDiagnostics(
            // Planted: the address must not appear.
            serverAddress = "https://books.alexanders-house.example",
            reportedVersion = "2.36.0",
            authMethods = listOf("local"),
            hasHandshake = true,
            confirmed = setOf(ServerCapability.Websocket),
            socketStatus = RealtimeStatus.Connected,
        ),
        storage = StorageDiagnostics(
            serversStored = 1,
            profilesStored = 2,
            storedCredentials = 2,
            librariesStored = 1,
            librariesAccessible = 1,
            booksStored = 412,
            booksAccessible = 412,
            booksSoftDeleted = 0,
            progressRecords = 88,
            unsyncedProgressRecords = 4,
        ),
    )
}
