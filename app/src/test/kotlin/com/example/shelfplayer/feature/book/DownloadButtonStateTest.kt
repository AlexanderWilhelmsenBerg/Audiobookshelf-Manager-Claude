package com.example.shelfplayer.feature.book

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadProgress
import com.example.shelfplayer.core.model.download.DownloadState
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.OfflineFile
import com.example.shelfplayer.domain.download.DownloadExecutionEvidence
import com.example.shelfplayer.domain.download.DownloadExecutionSnapshot
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * #202 — while a Download tap is pending, the button says so; the manifest's evidence always wins.
 *
 * Evidence winning is the property that matters: the pending flag is the screen's guess, the manifest is
 * what is actually on the device, and the guess must never hide a download that is already arriving or here.
 */
class DownloadButtonStateTest {

    @Test
    fun `without a pending tap every state is shown as the manifest has it`() {
        val states = listOf(
            DownloadButtonState.NotDownloaded,
            DownloadButtonState.OnDevice,
            DownloadButtonState.Downloading(progress = null),
            DownloadButtonState.Downloading(progress = 0.4f),
            DownloadButtonState.Paused(progress = 0.4f, percent = 40),
            DownloadButtonState.Downloaded,
            DownloadButtonState.Failed,
        )

        states.forEach { state -> assertEquals(state, state.whileStarting(isStarting = false)) }
    }

    @Test
    fun `a pending tap is shown at once over every state a tap can start from`() {
        listOf(
            DownloadButtonState.NotDownloaded,
            DownloadButtonState.OnDevice,
            DownloadButtonState.Failed,
            DownloadButtonState.Paused(progress = 0.4f, percent = 40),
        ).forEach { tapped ->
            assertEquals(DownloadButtonState.Starting, tapped.whileStarting(isStarting = true), "$tapped")
        }
    }

    @Test
    fun `the manifest's evidence of a download wins over a pending tap`() {
        val arriving = DownloadButtonState.Downloading(progress = 0.4f)
        val firstByte = DownloadButtonState.Downloading(progress = null)

        assertEquals(arriving, arriving.whileStarting(isStarting = true))
        assertEquals(firstByte, firstByte.whileStarting(isStarting = true))
        assertEquals(DownloadButtonState.Downloaded, DownloadButtonState.Downloaded.whileStarting(isStarting = true))
    }

    // ---- downloadButtonStateOf: the policy table ----

    /**
     * Revert-detector for "Retry sat on Failed for five seconds while it was actually downloading": the
     * manifest keeps saying Failed for the whole retry, and only WorkManager's evidence says otherwise.
     * A manifest-only mapping returns Failed here, and `whileStarting` then returns Starting.
     */
    @Test
    fun `a failed manifest being retried shows downloading, not failed or starting`() {
        val state = downloadButtonStateOf(
            offline = offlineBook(DownloadState.Failed),
            profileId = ADA,
            execution = snapshot(DownloadExecutionEvidence.Queued),
        )

        val expected = DownloadButtonState.Downloading(progress = 0.5f, percent = 50, phase = DownloadPhase.Queued)
        assertEquals(expected, state)
        assertEquals(state, state.whileStarting(isStarting = true))
    }

    @Test
    fun `a paused manifest shows paused with its percent`() {
        val state = downloadButtonStateOf(offlineBook(DownloadState.Paused), ADA, execution = null)

        assertEquals(DownloadButtonState.Paused(progress = 0.5f, percent = 50), state)
    }

    @Test
    fun `stale cancelled evidence never turns a paused download back into downloading`() {
        val state = downloadButtonStateOf(
            offline = offlineBook(DownloadState.Paused),
            profileId = ADA,
            execution = snapshot(DownloadExecutionEvidence.Cancelled),
        )

        assertEquals(DownloadButtonState.Paused(progress = 0.5f, percent = 50), state)
    }

    @Test
    fun `a paused download shown while a tap is pending reads as starting`() {
        val paused = downloadButtonStateOf(offlineBook(DownloadState.Paused), ADA, execution = null)

        assertEquals(DownloadButtonState.Starting, paused.whileStarting(isStarting = true))
    }

    @Test
    fun `the evidence names the phase`() {
        val phases = mapOf(
            DownloadExecutionEvidence.Queued to DownloadPhase.Queued,
            DownloadExecutionEvidence.Running to DownloadPhase.Transferring,
            DownloadExecutionEvidence.Waiting to DownloadPhase.Waiting,
            DownloadExecutionEvidence.Retrying to DownloadPhase.Retrying,
        )

        phases.forEach { (evidence, phase) ->
            val state = downloadButtonStateOf(offlineBook(DownloadState.Failed), ADA, snapshot(evidence))
            assertEquals(phase, (state as DownloadButtonState.Downloading).phase, "$evidence")
        }
    }

    @Test
    fun `a queued manifest with no evidence is queued`() {
        val state = downloadButtonStateOf(offlineBook(DownloadState.Queued), ADA, execution = null)

        assertEquals(DownloadPhase.Queued, (state as DownloadButtonState.Downloading).phase)
    }

    /** Finished or cancelled evidence is not enough to infer file state: the durable manifest decides. */
    @Test
    fun `finished evidence falls back to the failed manifest`() {
        val state = downloadButtonStateOf(
            offline = offlineBook(DownloadState.Failed),
            profileId = ADA,
            execution = snapshot(DownloadExecutionEvidence.Finished),
        )

        assertEquals(DownloadButtonState.Failed, state)
    }

    /** A float that rounds to 100 must still read 99 while the transfer is running. */
    @Test
    fun `live progress is floored and never reads 100 in flight`() {
        listOf(0.999f, 1f).forEach { fraction ->
            val live = DownloadProgress(downloadedBytes = 999, totalBytes = 1_000, fraction = fraction)

            val state = downloadButtonStateOf(
                offline = offlineBook(DownloadState.Running),
                profileId = ADA,
                execution = snapshot(DownloadExecutionEvidence.Running, live),
            )

            assertEquals(99, (state as DownloadButtonState.Downloading).percent, "fraction $fraction")
        }
    }

    @Test
    fun `before the first byte there is no fraction and the percent is zero`() {
        val state = downloadButtonStateOf(
            offline = offlineBook(DownloadState.Running, downloadedBytes = 0),
            profileId = ADA,
            execution = null,
        )

        val expected = DownloadButtonState.Downloading(progress = null, percent = 0, phase = DownloadPhase.Transferring)
        assertEquals(expected, state)
    }

    @Test
    fun `a complete copy wins over a failed manifest`() {
        val state = downloadButtonStateOf(offlineBook(DownloadState.Failed, complete = true), ADA, execution = null)

        assertEquals(DownloadButtonState.Downloaded, state)
    }

    @Test
    fun `another profile's complete copy can be used and claimed`() {
        val theirs = offlineBook(DownloadState.Complete, requestedBy = setOf(BEA), complete = true)
        val state = downloadButtonStateOf(theirs, ADA, execution = null)

        assertEquals(DownloadButtonState.OnDevice, state)
    }

    /** The orphan's lie: nobody claims it, so it is not "downloaded for another profile". */
    @Test
    fun `a complete copy nobody claims is not on device for another profile`() {
        val orphan = offlineBook(DownloadState.Complete, requestedBy = emptySet(), complete = true)
        val state = downloadButtonStateOf(orphan, ADA, execution = null)

        assertEquals(DownloadButtonState.NotDownloaded, state)
    }

    @Test
    fun `another profile's incomplete copy is not shown as ours`() {
        val theirs = offlineBook(DownloadState.Running, requestedBy = setOf(BEA))
        val state = downloadButtonStateOf(theirs, ADA, execution = null)

        assertEquals(DownloadButtonState.NotDownloaded, state)
    }

    @Test
    fun `no manifest or no profile is not downloaded`() {
        assertEquals(DownloadButtonState.NotDownloaded, downloadButtonStateOf(null, ADA, null))
        val running = offlineBook(DownloadState.Running)
        assertEquals(DownloadButtonState.NotDownloaded, downloadButtonStateOf(running, null, null))
    }

    /**
     * The tap table. Revert-detectors: Downloading must ask (the old code cancelled on tap), and Paused
     * must start (the old code showed a paused book as Downloading, whose tap did nothing useful).
     */
    @Test
    fun `taps start, ask or are ignored by state`() {
        val expected = mapOf<DownloadButtonState, DownloadTap>(
            DownloadButtonState.NotDownloaded to DownloadTap.Start,
            DownloadButtonState.OnDevice to DownloadTap.Start,
            DownloadButtonState.Failed to DownloadTap.Start,
            DownloadButtonState.Paused(progress = 0.3f, percent = 30) to DownloadTap.Start,
            DownloadButtonState.Downloading(progress = null) to DownloadTap.AskPauseOrStop,
            DownloadButtonState.Downloading(progress = 0.4f, percent = 40) to DownloadTap.AskPauseOrStop,
            DownloadButtonState.Downloaded to DownloadTap.AskRemove,
            DownloadButtonState.Starting to DownloadTap.Ignore,
        )

        expected.forEach { (state, tap) -> assertEquals(tap, state.tap(), "$state") }
    }

    private fun snapshot(evidence: DownloadExecutionEvidence, progress: DownloadProgress? = null) =
        DownloadExecutionSnapshot(evidence = evidence, progress = progress)

    private fun offlineBook(
        state: DownloadState,
        requestedBy: Set<ProfileId> = setOf(ADA),
        downloadedBytes: Long = 512,
        expectedBytes: Long = 1_024,
        complete: Boolean = false,
    ) = OfflineBook(
        serverId = ServerId("server-1"),
        itemId = LibraryItemId("book-1"),
        state = state,
        failureSummary = null,
        storageVolumeUuid = null,
        files = listOf(
            OfflineFile(
                remoteFileId = "file-1",
                index = 0,
                uri = "file:///downloads/1.mp3",
                state = if (complete) DownloadState.Complete else DownloadState.Running,
                expectedBytes = expectedBytes,
                downloadedBytes = if (complete) expectedBytes else downloadedBytes,
                mimeType = "audio/mpeg",
                duration = null,
                eTag = null,
                lastModified = null,
            ),
        ),
        coverUri = null,
        requestedBy = requestedBy,
        isPinned = false,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private companion object {
        val ADA = ProfileId("ada")
        val BEA = ProfileId("bea")
    }
}
