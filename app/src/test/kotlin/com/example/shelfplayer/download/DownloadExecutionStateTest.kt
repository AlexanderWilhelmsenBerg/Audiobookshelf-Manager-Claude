package com.example.shelfplayer.download

import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.example.shelfplayer.domain.download.DownloadExecutionEvidence
import org.junit.Test
import kotlin.test.assertEquals

class DownloadExecutionStateTest {

    @Test
    fun `enqueued work distinguishes queued retrying and allowed-network waiting`() {
        assertEquals(
            DownloadExecutionEvidence.Queued,
            classify(WorkInfo.State.ENQUEUED, attempts = 0, NetworkType.CONNECTED, online = true),
        )
        assertEquals(
            DownloadExecutionEvidence.Retrying,
            classify(WorkInfo.State.ENQUEUED, attempts = 1, NetworkType.CONNECTED, online = true),
        )
        assertEquals(
            DownloadExecutionEvidence.Waiting,
            classify(WorkInfo.State.ENQUEUED, attempts = 0, NetworkType.CONNECTED, online = false),
        )
        assertEquals(
            DownloadExecutionEvidence.Waiting,
            classify(
                WorkInfo.State.ENQUEUED,
                attempts = 4,
                required = NetworkType.UNMETERED,
                online = true,
                unmetered = false,
            ),
            "an unmet allowed-network constraint outranks retry/backoff",
        )
    }

    @Test
    fun `running blocked and terminal states preserve the readiness contract`() {
        assertEquals(
            DownloadExecutionEvidence.Running,
            classify(WorkInfo.State.RUNNING, attempts = 0, NetworkType.UNMETERED, online = false),
        )
        assertEquals(
            DownloadExecutionEvidence.Queued,
            classify(WorkInfo.State.BLOCKED, attempts = 2, NetworkType.UNMETERED, online = false),
            "BLOCKED is prerequisite evidence, not proof of network waiting",
        )
        assertEquals(
            DownloadExecutionEvidence.Finished,
            classify(WorkInfo.State.SUCCEEDED, attempts = 1, NetworkType.CONNECTED, online = true),
        )
        assertEquals(
            DownloadExecutionEvidence.Finished,
            classify(WorkInfo.State.FAILED, attempts = 1, NetworkType.CONNECTED, online = true),
        )
        assertEquals(
            DownloadExecutionEvidence.Cancelled,
            classify(WorkInfo.State.CANCELLED, attempts = 0, NetworkType.CONNECTED, online = true),
        )
    }

    @Test
    fun `unmetered requires both connectivity and unmetered network`() {
        assertEquals(
            DownloadExecutionEvidence.Waiting,
            classify(WorkInfo.State.ENQUEUED, 0, NetworkType.UNMETERED, online = false, unmetered = true),
        )
        assertEquals(
            DownloadExecutionEvidence.Waiting,
            classify(WorkInfo.State.ENQUEUED, 0, NetworkType.UNMETERED, online = true, unmetered = false),
        )
        assertEquals(
            DownloadExecutionEvidence.Queued,
            classify(WorkInfo.State.ENQUEUED, 0, NetworkType.UNMETERED, online = true, unmetered = true),
        )
    }

    private fun classify(
        state: WorkInfo.State,
        attempts: Int,
        required: NetworkType,
        online: Boolean,
        unmetered: Boolean = online,
    ): DownloadExecutionEvidence = classifyDownloadWork(
        work = DownloadWorkSnapshot(
            state = state,
            runAttemptCount = attempts,
            requiredNetworkType = required,
        ),
        network = DownloadNetworkSnapshot(isOnline = online, isUnmetered = unmetered),
    )
}
