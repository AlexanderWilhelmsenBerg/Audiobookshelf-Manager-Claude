package com.example.shelfplayer.feature.downloads

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.domain.download.DownloadRecoveryState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** BW-DL-03 / #18 — recovery controls say exactly what a tap will do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DownloadsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `running row exposes Pause only`() {
        render(DownloadRecoveryState.Running)

        compose.onNodeWithContentDescription("Pause this download").assertExists()
        compose.onNodeWithContentDescription("Resume this download").assertDoesNotExist()
        compose.onNodeWithContentDescription("Retry this download").assertDoesNotExist()
    }

    @Test
    fun `paused row exposes Resume only`() {
        render(DownloadRecoveryState.Paused)

        compose.onNodeWithContentDescription("Resume this download").assertExists()
        compose.onNodeWithContentDescription("Pause this download").assertDoesNotExist()
        compose.onNodeWithContentDescription("Retry this download").assertDoesNotExist()
    }

    @Test
    fun `failed row exposes Retry and never Pause`() {
        var observed: DownloadRecoveryState? = null
        render(DownloadRecoveryState.Failed) { state -> observed = state }

        compose.onNodeWithContentDescription("Retry this download").performClick()

        assertEquals(DownloadRecoveryState.Failed, observed)
        compose.onNodeWithContentDescription("Pause this download").assertDoesNotExist()
        compose.onNodeWithContentDescription("Resume this download").assertDoesNotExist()
    }

    @Test
    fun `queued waiting retrying and complete rows expose no recovery action`() {
        var state = DownloadRecoveryState.Queued
        compose.setContent {
            DownloadsScreen(
                uiState = state(state),
                onRemove = { _, _ -> },
                onPinnedChanged = { _, _, _ -> },
                onRecoveryAction = { _, _ -> },
                onVerify = {},
                onNavigateUp = {},
            )
        }

        listOf(
            DownloadRecoveryState.Queued,
            DownloadRecoveryState.Waiting,
            DownloadRecoveryState.Retrying,
            DownloadRecoveryState.Complete,
        ).forEach { next ->
            state = next
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Pause this download").assertDoesNotExist()
            compose.onNodeWithContentDescription("Resume this download").assertDoesNotExist()
            compose.onNodeWithContentDescription("Retry this download").assertDoesNotExist()
        }
    }

    private fun render(
        recoveryState: DownloadRecoveryState,
        onRecovery: (DownloadRecoveryState) -> Unit = {},
    ) {
        compose.setContent {
            DownloadsScreen(
                uiState = state(recoveryState),
                onRemove = { _, _ -> },
                onPinnedChanged = { _, _, _ -> },
                onRecoveryAction = { _, state -> onRecovery(state) },
                onVerify = {},
                onNavigateUp = {},
            )
        }
    }

    private fun state(recoveryState: DownloadRecoveryState) = DownloadsUiState(
        books = listOf(
            DownloadRow(
                bookId = BOOK,
                serverId = SERVER,
                title = "Tidewatch",
                author = "Marisol Holt",
                fileCount = 1,
                bytes = 512,
                isComplete = recoveryState == DownloadRecoveryState.Complete,
                recoveryState = recoveryState,
                failureSummary = "The connection was lost.".takeIf {
                    recoveryState == DownloadRecoveryState.Failed
                },
                isPinned = false,
                isSharedWithAnotherProfile = false,
            ),
        ),
        totalBytes = 512,
        isLoaded = true,
    )

    private companion object {
        val BOOK = LibraryItemId("tidewatch")
        val SERVER = ServerId("srv_books")
    }
}
