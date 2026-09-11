package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.download.DownloadState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** BW-DL-02 / #107 — durable manifest state and transient execution evidence stay separate. */
class DownloadRecoveryPolicyTest {

    @Test
    fun `durable states resolve without execution evidence`() {
        val expected = mapOf(
            DownloadState.Queued to DownloadRecoveryState.Queued,
            DownloadState.Running to DownloadRecoveryState.Running,
            DownloadState.Complete to DownloadRecoveryState.Complete,
            DownloadState.Failed to DownloadRecoveryState.Failed,
            DownloadState.Paused to DownloadRecoveryState.Paused,
        )

        expected.forEach { (durable, recovery) ->
            val presentation = DownloadRecoveryPolicy.resolve(
                durableState = durable,
                manifestFilesComplete = durable == DownloadState.Complete,
                safeFailureSummary = FAILURE,
            )

            assertEquals(recovery, presentation.state, durable.name)
            if (recovery == DownloadRecoveryState.Failed) {
                assertEquals(FAILURE, presentation.failureSummary)
            } else {
                assertNull(presentation.failureSummary, "a stale failure must not bleed into ${recovery.name}")
            }
        }
    }

    @Test
    fun `complete book row with non complete file evidence fails conservatively`() {
        DownloadExecutionEvidence.entries.forEach { execution ->
            val presentation = DownloadRecoveryPolicy.resolve(
                durableState = DownloadState.Complete,
                manifestFilesComplete = false,
                safeFailureSummary = null,
                executionEvidence = execution,
            )

            assertEquals(DownloadRecoveryState.Failed, presentation.state, "must not trust $execution over files")
            assertNull(presentation.failureSummary)
        }
    }

    @Test
    fun `active execution evidence refines non authoritative durable state`() {
        val expected = mapOf(
            DownloadExecutionEvidence.Queued to DownloadRecoveryState.Queued,
            DownloadExecutionEvidence.Running to DownloadRecoveryState.Running,
            DownloadExecutionEvidence.Waiting to DownloadRecoveryState.Waiting,
            DownloadExecutionEvidence.Retrying to DownloadRecoveryState.Retrying,
        )

        listOf(DownloadState.Queued, DownloadState.Running, DownloadState.Failed).forEach { durable ->
            expected.forEach { (execution, recovery) ->
                val presentation = DownloadRecoveryPolicy.resolve(
                    durableState = durable,
                    manifestFilesComplete = false,
                    safeFailureSummary = FAILURE,
                    executionEvidence = execution,
                )

                assertEquals(recovery, presentation.state, "$durable + $execution")
                assertNull(presentation.failureSummary, "automatic work is not a terminal failure")
            }
        }
    }

    @Test
    fun `complete and paused durable evidence wins over stale execution evidence`() {
        listOf(DownloadState.Complete, DownloadState.Paused).forEach { durable ->
            val expected = if (durable == DownloadState.Complete) {
                DownloadRecoveryState.Complete
            } else {
                DownloadRecoveryState.Paused
            }

            DownloadExecutionEvidence.entries.forEach { execution ->
                val presentation = DownloadRecoveryPolicy.resolve(
                    durableState = durable,
                    manifestFilesComplete = durable == DownloadState.Complete,
                    safeFailureSummary = FAILURE,
                    executionEvidence = execution,
                )

                assertEquals(expected, presentation.state, "$durable must win over $execution")
                assertNull(presentation.failureSummary)
            }
        }
    }

    @Test
    fun `terminal execution evidence falls back to durable manifest state`() {
        listOf(DownloadExecutionEvidence.Finished, DownloadExecutionEvidence.Cancelled).forEach { execution ->
            assertEquals(
                DownloadRecoveryState.Queued,
                DownloadRecoveryPolicy.resolve(DownloadState.Queued, false, null, execution).state,
            )
            assertEquals(
                DownloadRecoveryState.Running,
                DownloadRecoveryPolicy.resolve(DownloadState.Running, false, null, execution).state,
            )
            val failed = DownloadRecoveryPolicy.resolve(DownloadState.Failed, false, FAILURE, execution)
            assertEquals(DownloadRecoveryState.Failed, failed.state)
            assertEquals(FAILURE, failed.failureSummary)
        }
    }

    private companion object {
        const val FAILURE = "The connection was lost. Check the server and try again."
    }
}
