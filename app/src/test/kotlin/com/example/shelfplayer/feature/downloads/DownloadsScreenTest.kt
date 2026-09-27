package com.example.shelfplayer.feature.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.DownloadProgress
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
    fun `execution states render explicit listener-facing status copy`() {
        var recoveryState by mutableStateOf(DownloadRecoveryState.Queued)
        compose.setContent {
            DownloadsScreen(
                uiState = state(recoveryState),
                onRemove = { _, _ -> },
                onPinnedChanged = { _, _, _ -> },
                onRecoveryAction = { _, _ -> },
                onVerify = {},
                onNavigateUp = {},
            )
        }

        val expected = listOf(
            DownloadRecoveryState.Queued to "Queued",
            DownloadRecoveryState.Running to "Downloading",
            DownloadRecoveryState.Waiting to "Waiting for an allowed network",
            DownloadRecoveryState.Retrying to "Retrying automatically",
            DownloadRecoveryState.Paused to "Paused",
        )

        expected.forEach { (state, text) ->
            recoveryState = state
            compose.waitForIdle()
            compose.onNodeWithText(text, substring = true).assertExists()
        }
    }

    @Test
    fun `hidden failed row uses generic failure copy instead of media context`() {
        compose.setContent {
            DownloadsScreen(
                uiState = state(DownloadRecoveryState.Failed).copy(
                    books = listOf(
                        state(DownloadRecoveryState.Failed).books.single().copy(
                            title = null,
                            author = null,
                            failureSummary = null,
                        ),
                    ),
                ),
                onRemove = { _, _ -> },
                onPinnedChanged = { _, _, _ -> },
                onRecoveryAction = { _, _ -> },
                onVerify = {},
                onNavigateUp = {},
            )
        }

        compose.onNodeWithText("Download failed", substring = true).assertExists()
        compose.onNodeWithText("The connection was lost.", substring = true).assertDoesNotExist()
    }

    @Test
    fun `paused row with real partial bytes exposes confirmed discard action`() {
        var discarded = false
        compose.setContent {
            DownloadsScreen(
                uiState = state(DownloadRecoveryState.Paused).copy(
                    books = listOf(
                        state(DownloadRecoveryState.Paused).books.single().copy(partialBytes = 4_096L),
                    ),
                ),
                onRemove = { _, _ -> },
                onPinnedChanged = { _, _, _ -> },
                onRecoveryAction = { _, _ -> },
                onDiscardPartials = { _, _ -> discarded = true },
                onVerify = {},
                onNavigateUp = {},
            )
        }

        compose.onNodeWithText("Discard 4.0 kB partial").performClick()
        compose.onNodeWithText("Discard partial download?").assertExists()
        compose.onNodeWithText("Completed audio stays on this device", substring = true).assertExists()
        compose.onNodeWithText("Discard partial").performClick()

        assertEquals(true, discarded)
    }

    @Test
    fun `discard action is absent without reclaimable partial bytes`() {
        render(DownloadRecoveryState.Paused)

        compose.onNodeWithText("Discard", substring = true).assertDoesNotExist()
    }

    @Test
    fun `active and on-device rows are separated and simultaneous progress stays independent`() {
        val first = state(DownloadRecoveryState.Running).books.single().copy(
            bookId = LibraryItemId("first"),
            title = "First",
            progress = DownloadProgress(downloadedBytes = 256L, totalBytes = 1_024L, fraction = 0.25f),
        )
        val second = state(DownloadRecoveryState.Running).books.single().copy(
            bookId = LibraryItemId("second"),
            title = "Second",
            progress = DownloadProgress(downloadedBytes = 768L, totalBytes = 1_024L, fraction = 0.75f),
        )
        val stored = state(DownloadRecoveryState.Complete).books.single().copy(
            bookId = LibraryItemId("stored"),
            title = "Stored",
        )
        compose.setContent {
            DownloadsScreen(
                uiState = DownloadsUiState(
                    books = listOf(first, second, stored),
                    totalBytes = 2_048L,
                    isLoaded = true,
                ),
                onRemove = { _, _ -> },
                onPinnedChanged = { _, _, _ -> },
                onRecoveryAction = { _, _ -> },
                onVerify = {},
                onNavigateUp = {},
            )
        }

        compose.onNodeWithText("Active / pending").assertExists()
        compose.onNodeWithText("On device").assertExists()
        compose.onNodeWithText("256 B / 1.0 kB").assertExists()
        compose.onNodeWithText("768 B / 1.0 kB").assertExists()
    }

    @Test
    fun `another profiles copy is visible but has no profile-scoped remove or recovery action`() {
        compose.setContent {
            DownloadsScreen(
                uiState = state(DownloadRecoveryState.Complete).copy(
                    books = listOf(
                        state(DownloadRecoveryState.Complete).books.single().copy(
                            isClaimedByActiveProfile = false,
                            isOnDeviceForAnotherProfile = true,
                        ),
                    ),
                ),
                onRemove = { _, _ -> },
                onPinnedChanged = { _, _, _ -> },
                onRecoveryAction = { _, _ -> },
                onVerify = {},
                onNavigateUp = {},
            )
        }

        compose.onNodeWithText("Downloaded on this device for another profile", substring = true).assertExists()
        compose.onNodeWithContentDescription("Remove this download").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause this download").assertDoesNotExist()
        compose.onNodeWithContentDescription("Resume this download").assertDoesNotExist()
        compose.onNodeWithContentDescription("Retry this download").assertDoesNotExist()
    }

    @Test
    fun `queued waiting retrying and complete rows expose no recovery action`() {
        var recoveryState by mutableStateOf(DownloadRecoveryState.Queued)
        compose.setContent {
            DownloadsScreen(
                uiState = state(recoveryState),
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
            recoveryState = next
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Pause this download").assertDoesNotExist()
            compose.onNodeWithContentDescription("Resume this download").assertDoesNotExist()
            compose.onNodeWithContentDescription("Retry this download").assertDoesNotExist()
        }
    }

    private fun render(recoveryState: DownloadRecoveryState, onRecovery: (DownloadRecoveryState) -> Unit = {}) {
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
