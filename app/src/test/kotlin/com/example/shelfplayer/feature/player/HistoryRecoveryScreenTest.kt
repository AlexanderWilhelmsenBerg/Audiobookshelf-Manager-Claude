package com.example.shelfplayer.feature.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.core.graphics.createBitmap
import com.example.shelfplayer.core.designsystem.theme.ShelfPlayerTheme
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.model.playback.SleepTimerState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h1280dp")
class HistoryRecoveryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(fontScale = 1.3f)
    fun `native history row renders date time and book progress together`() {
        lateinit var view: View
        val event = PlaybackHistoryEntry(
            "entry",
            PlaybackEvent.ChapterCrossed,
            null,
            20.minutes,
            null,
            Instant.parse("2026-10-03T20:10:25Z"),
        )
        compose.setContent {
            ShelfPlayerTheme(darkTheme = false, dynamicColor = false) {
                view = LocalView.current
                Surface { HistoryRow(event, null, emptyList(), {}, Modifier.testTag("history-row"), 40.minutes) }
            }
        }
        compose.onNodeWithText("50% of book", useUnmergedTree = true).assertIsDisplayed()
        val bounds = compose.onNodeWithTag("history-row").getBoundsInRoot()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val density = view.resources.displayMetrics.density
            image =
                createBitmap(
                    (bounds.right - bounds.left).value.times(density).toInt(),
                    (bounds.bottom - bounds.top).value.times(density).toInt(),
                    Bitmap.Config.ARGB_8888,
                )
            val canvas = Canvas(image)
            canvas.translate(-bounds.left.value * density, -bounds.top.value * density)
            view.draw(canvas)
        }
        val directory = File("build/series-screen-evidence").apply { mkdirs() }
        File(directory, "history-event-375dp-font1.3.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test
    fun `history displays saved progress chapter crossing percent and event date`() {
        val event = PlaybackHistoryEntry(
            "entry",
            PlaybackEvent.ChapterCrossed,
            null,
            20.minutes,
            null,
            Instant.parse("2026-10-03T20:10:25Z"),
        )
        compose.setContent {
            MaterialTheme { HistorySheet(listOf(event), emptyList(), {}, {}, duration = 40.minutes) }
        }
        compose.onNodeWithText("Chapter crossed", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("50% of book", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("2026", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `paused timer chooser disables creation but keeps off available`() {
        compose.setContent { MaterialTheme { SleepTimerSheet(SleepTimerState.Idle, {}, {}, canStart = false) } }
        compose.onNodeWithText("30 min").assertIsNotEnabled()
        compose.onNodeWithText("End of chapter").assertIsNotEnabled()
        compose.onNodeWithText("Off").assertIsDisplayed()
    }

    @Test
    fun `progress uses the event position and unknown durations have no invented percentage`() {
        assertEquals(50, historyProgressPercent(20.minutes, 40.minutes))
        assertEquals(25, historyProgressPercent(10.minutes, 40.minutes))
        assertEquals(100, historyProgressPercent(60.minutes, 40.minutes))
        assertNull(historyProgressPercent(10.minutes, Duration.ZERO))
    }
}
